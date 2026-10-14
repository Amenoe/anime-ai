#!/usr/bin/env python3
"""测量导入吞吐（M2 验收标准⑤：冷启动耗时）。

用**虚构 subjectId + 真实语料文本**来测：
- 文本是真的（3000 条语料里抽的），所以 embedding 负载真实
- id 是虚构的（默认 993000 起），所以不会碰真实番剧的点，测完即删

若用真实 id 传回同样的文本，内容 hash 未变会被跳过，**测不到任何 embedding 耗时** ——
这是设计使然（幂等），但会让「冷启动测量」悄悄变成「测了个空」。

用法：
    python3 eval/measure_import.py --sample 300
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import time
import urllib.request
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
REPO_ROOT = EVAL_DIR.parent
INGEST_URL = os.environ.get("INGEST_URL", "http://127.0.0.1:8013/internal/knowledge/ingest")
QDRANT = os.environ.get("QDRANT_URL", "http://127.0.0.1:6333")
COLLECTION = os.environ.get("QDRANT_COLLECTION", "anime_knowledge")


def load_token() -> str:
    if os.environ.get("AI_INTERNAL_TOKEN"):
        return os.environ["AI_INTERNAL_TOKEN"]
    for line in (REPO_ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if line.strip().startswith("AI_INTERNAL_TOKEN="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


def qdrant(path: str, body: dict) -> dict:
    req = urllib.request.Request(
        f"{QDRANT}{path}",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def sample_texts(n: int) -> list[str]:
    """从真实语料里抽 n 条文本（只要 text，不带 id）。"""
    texts, offset = [], None
    while len(texts) < n:
        body = {"limit": min(500, n * 2), "with_payload": True, "with_vector": False}
        if offset is not None:
            body["offset"] = offset
        page = qdrant(f"/collections/{COLLECTION}/points/scroll", body)["result"]
        batch = page.get("points", [])
        if not batch:
            break
        texts.extend(p["payload"].get("text") or "" for p in batch)
        offset = page.get("next_page_offset")
        if offset is None:
            break
    return [t for t in texts if t][:n]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", type=int, default=300, help="抽样条数（越大越接近冷启动）")
    ap.add_argument("--base-id", type=int, default=993000)
    args = ap.parse_args()

    token = load_token()
    texts = sample_texts(args.sample)
    if not texts:
        print("抽不到语料文本 —— 集合是空的？")
        return 1

    docs = [
        {
            "subjectId": args.base_id + i,
            "text": t,
            "payload": {"subject_id": args.base_id + i, "name": f"吞吐测量-{i}"},
        }
        for i, t in enumerate(texts)
    ]
    ids = [d["subjectId"] for d in docs]
    total_chars = sum(len(d["text"]) for d in docs)

    # 干净起点：上一轮异常退出可能留下点
    qdrant(f"/collections/{COLLECTION}/points/delete", {"points": ids})

    try:
        print(f"抽样 {len(docs)} 条真实语料（共 {total_chars} 字），开始冷启动导入…")
        started = time.monotonic()
        req = urllib.request.Request(
            INGEST_URL,
            data=json.dumps({"documents": docs}).encode("utf-8"),
            headers={"Content-Type": "application/json", "X-Internal-Token": token},
        )
        with urllib.request.urlopen(req, timeout=900) as resp:
            result = json.loads(resp.read().decode("utf-8"))
        elapsed = time.monotonic() - started

        upserted = result.get("upserted")
        print(f"  upserted={upserted}  loaded={result.get('loaded')}  耗时={elapsed:.1f}s")
        per_doc = elapsed / len(docs) * 1000
        print(f"  每条 {per_doc:.0f}ms；按此外推 3000 条 ≈ {elapsed / len(docs) * 3000 / 60:.1f} 分钟")

        # 分批开销：batch-size 默认 64，估算批次与每批耗时
        batches = (len(docs) + 63) // 64
        print(f"  {len(docs)} 条 / 每批 64 = {batches} 批，每批约 {elapsed / batches:.1f}s")

        print("\n=== 供文档记录 ===")
        print(f"  实测样本：{len(docs)} 条 / {total_chars} 字 / {elapsed:.1f}s")
        print(f"  外推 3000 条：{elapsed / len(docs) * 3000 / 60:.1f} 分钟（线性外推，非实测）")
        print(f"  ⚠️ 该数字取决于 SiliconFlow 当时的速度与网络，换时间跑会有出入")
    finally:
        qdrant(f"/collections/{COLLECTION}/points/delete", {"points": ids})
        print(f"\n（已清理测试点 {ids[0]}..{ids[-1]}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
