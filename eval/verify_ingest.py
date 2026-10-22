#!/usr/bin/env python3
"""验证 M2 验收标准 ③幂等 与 ④增量（改一条只更新该条）。

为什么需要它：`upserted` 是唯一能区分「真的重新算了向量」与「跳过了未变化项」的信号，
而它同时受「内容是否变化」和「Qdrant 里有没有旧点」两个因素影响。只跑一次导入
（`upserted=0`）无法证明增量逻辑正确 —— 必须**改变源内容**再跑一次。

做法：直接往 `/internal/knowledge/ingest` 传 `documents`（接口支持），
用**虚构的 subjectId**（默认 990001 起），因此不会污染真实的番剧语料；
脚本结束时会把这些测试点从 Qdrant 删掉。

用法：
    python3 eval/verify_ingest.py
    python3 eval/verify_ingest.py --base-id 991000

退出码非 0 表示有用例失败。这是**断言**（不是评估），所以用错误码表达失败。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
REPO_ROOT = EVAL_DIR.parent
INGEST_URL = os.environ.get("INGEST_URL", "http://127.0.0.1:8013/internal/knowledge/ingest")
QDRANT = os.environ.get("QDRANT_URL", "http://127.0.0.1:6333")
COLLECTION = os.environ.get("QDRANT_COLLECTION", "anime_knowledge")

passed = 0
failed = 0


def check(name: str, ok: bool, extra: str = "") -> None:
    global passed, failed
    if ok:
        passed += 1
        print(f"  ✅ {name}")
    else:
        failed += 1
        print(f"  ❌ {name} {extra}")


def load_token() -> str:
    if os.environ.get("AI_INTERNAL_TOKEN"):
        return os.environ["AI_INTERNAL_TOKEN"]
    env_file = REPO_ROOT / ".env"
    if not env_file.exists():
        return ""
    for line in env_file.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith("AI_INTERNAL_TOKEN="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


def post(url: str, body: dict, token: str = "") -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Internal-Token"] = token
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers)
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_point(sid: int) -> dict | None:
    req = urllib.request.Request(
        f"{QDRANT}/collections/{COLLECTION}/points",
        data=json.dumps({"ids": [sid], "with_payload": True, "with_vector": False}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        result = json.loads(resp.read().decode("utf-8")).get("result", [])
    return result[0]["payload"] if result else None


def delete_points(ids: list[int]) -> None:
    req = urllib.request.Request(
        f"{QDRANT}/collections/{COLLECTION}/points/delete",
        data=json.dumps({"points": ids}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    urllib.request.urlopen(req, timeout=30).read()


def doc(sid: int, text: str) -> dict:
    return {
        "subjectId": sid,
        "text": text,
        "payload": {"subject_id": sid, "name": f"增量验证-{sid}", "name_cn": f"增量验证-{sid}"},
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-id", type=int, default=990001)
    ap.add_argument("--keep", action="store_true", help="保留测试点（默认会清理）")
    args = ap.parse_args()

    a, b = args.base_id, args.base_id + 1
    token = load_token()
    text_a = "增量验证 甲版本：写实系剑戟片，主角是流浪剑客。"
    text_b = "增量验证 乙文档：与甲无关，用于确认它不会被误更新。"

    try:
        # 先确保干净起点：上一轮若异常退出可能留下测试点，
        # 不清掉的话「首轮 upserted=2」会因为点已存在而变成 0（本脚本踩过这个自污染）。
        delete_points([a, b])

        # 只探活，不要用 ingest 探活 —— 那会先写一次，让「首轮」变成第二轮
        # 从 INGEST_URL 推导服务根地址：否则指定了别的端口探活却打到默认端口，
        # 会出现「换端口跑实验时，探活成功但实际打的是另一个实例」。
        post(f"{INGEST_URL.rsplit(chr(47) + 'internal' + chr(47), 1)[0]}/internal/ai/ping", {}, token)
    except urllib.error.HTTPError as exc:
        print(f"服务返回 HTTP {exc.code}：{exc.read().decode('utf-8', 'ignore')[:200]}")
        return 1
    except Exception as exc:  # noqa: BLE001
        print(f"服务不可达（anime-ai 在跑吗？）: {exc}")
        return 1

    try:
        # ── ③ 幂等 / ④ 首轮写入 ───────────────────────────────
        print("\n-- 首轮：两条新文档 --")
        r1 = post(INGEST_URL, {"documents": [doc(a, text_a), doc(b, text_b)]}, token)
        check("首轮 upserted = 2（两条都要算向量）", r1.get("upserted") == 2, f"实际 {r1}")

        print("\n-- 幂等：同样的内容再导一次 --")
        r2 = post(INGEST_URL, {"documents": [doc(a, text_a), doc(b, text_b)]}, token)
        check("重复导入 upserted = 0（全部跳过）", r2.get("upserted") == 0, f"实际 {r2}")
        check("重复导入 loaded 仍为 2（不是读不到语料）", r2.get("loaded") == 2, f"实际 {r2}")

        # ── ④ 增量：只改一条 ───────────────────────────────────
        print("\n-- 增量：只改甲的内容，乙保持不变 --")
        text_a2 = "增量验证 甲版本二：改成了时间循环题材，主角被困在同一天。"
        r3 = post(INGEST_URL, {"documents": [doc(a, text_a2), doc(b, text_b)]}, token)
        check("只更新变化的那一条（upserted = 1 而非 2）", r3.get("upserted") == 1, f"实际 {r3}")

        pa = fetch_point(a)
        pb = fetch_point(b)
        check("甲的 payload.text 已更新", (pa or {}).get("text") == text_a2, str((pa or {}).get("text"))[:60])
        check("甲的 content_hash 已更新", bool((pa or {}).get("content_hash")) and (pa or {}).get("content_hash") != (pb or {}).get("content_hash"))
        check("乙的 payload.text 未被动过", (pb or {}).get("text") == text_b, str((pb or {}).get("text"))[:60])
        check("两点都存在（更新不是删除+重写）", pa is not None and pb is not None)

        # 改后的内容应能被检索到，且只召回甲
        hits = post(
            "http://127.0.0.1:8013/internal/retrieve",
            {"query": "被困在同一天的时间循环", "topK": 5},
            token,
        ).get("hits") or []
        ids = [h.get("subject_id") for h in hits]
        check(f"改后的内容可被检索召回（top5 含 {a}）", a in ids, f"实际 top5={ids}")

        print(f"\n结果：{passed} 通过 / {failed} 失败")
        return 0 if failed == 0 else 1
    finally:
        if not args.keep:
            delete_points([a, b])
            print(f"（已清理测试点 {a} / {b}）")


if __name__ == "__main__":
    sys.exit(main())
