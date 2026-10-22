#!/usr/bin/env python3
"""M3 检索质量评估：用 golden set 打 /internal/retrieve，算 Hit Rate@k 与 MRR。

设计要点：
- **只调 `/internal/retrieve`，不碰 LLM**。这正是 M2 把「检索」与「生成」拆成两个接口的原因
  （roadmap §3.6⑤）：评估不该依赖生成质量，也不该为评估烧对话模型的 token。
- **不引入 ragas**。ragas 主要面向「有 LLM 生成」的评估（答案忠实度、相关性等），
  对纯检索的 Hit Rate / MRR 是多余依赖；自己算更简单、更快、结果可复现。
- **按类别分别报告**。作品名查询近乎查表（是下限），概念查询才是向量检索的主考题；
  混在一起平均会掩盖问题。
- 输出 JSON 便于把「改语料前/后」两次运行做对照 —— 语料改造方案要靠它来选，不能靠推理。

用法：
    python3 eval/run_eval.py                  # 默认 topK=10
    python3 eval/run_eval.py --topk 5
    python3 eval/run_eval.py --json out.json  # 同时落盘结果
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
DEFAULT_GOLDEN = EVAL_DIR / "golden-set.json"
DEFAULT_URL = os.environ.get("RETRIEVE_URL", "http://127.0.0.1:8013/internal/retrieve")


def load_internal_token() -> str:
    """从 repo 根的 .env 读内部令牌（不打印、不外传）。"""
    if os.environ.get("AI_INTERNAL_TOKEN"):
        return os.environ["AI_INTERNAL_TOKEN"]
    env_file = REPO_ROOT / ".env"
    if not env_file.exists():
        return ""
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("AI_INTERNAL_TOKEN="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


def retrieve(url: str, token: str, query: str, top_k: int, strategy: str | None = None) -> list[dict]:
    payload: dict = {"query": query, "topK": top_k}
    if strategy:
        payload["strategy"] = strategy
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json", "X-Internal-Token": token},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8")).get("hits") or []


def first_hit_rank(hits: list[dict], expect: list[int]) -> int | None:
    """返回第一个命中的 1-based 排名；无命中返回 None。"""
    for i, h in enumerate(hits, 1):
        if h.get("subject_id") in expect:
            return i
    return None


def label(hit: dict) -> str:
    p = hit.get("payload") or {}
    return p.get("name_cn") or p.get("name") or f"#{hit.get('subject_id')}"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN)
    ap.add_argument("--url", default=DEFAULT_URL)
    ap.add_argument("--topk", type=int, default=10)
    ap.add_argument("--json", type=Path, default=None, help="把结果落盘，便于两次运行对照")
    ap.add_argument("--quiet", action="store_true", help="只打汇总，不打每条明细")
    ap.add_argument(
        "--strategy",
        default=None,
        help="检索策略：vector(默认) / hybrid / rrf / hybrid-rrf。服务端留空即 vector")
    args = ap.parse_args()

    golden = json.loads(args.golden.read_text(encoding="utf-8"))
    token = load_internal_token()

    try:
        retrieve(args.url, token, "连通性探针", 1)
    except urllib.error.HTTPError as exc:
        print(f"探针返回 HTTP {exc.code} —— 内部令牌可能不对")
        return 1
    except Exception as exc:  # noqa: BLE001
        print(f"探针失败（anime-ai 在跑吗？）: {exc}")
        return 1

    per_query, by_cat = [], {}
    hits_at = 0
    rr_sum = 0.0

    for q in golden["queries"]:
        hits = retrieve(args.url, token, q["query"], args.topk, args.strategy)
        rank = first_hit_rank(hits, q["expect"])
        hit = rank is not None
        hits_at += 1 if hit else 0
        rr_sum += (1.0 / rank) if rank else 0.0
        rec = {
            "id": q["id"],
            "category": q["category"],
            "query": q["query"],
            "expect": q["expect"],
            "hit": hit,
            "rank": rank,
            "top": [
                {
                    "subject_id": h.get("subject_id"),
                    "name": label(h),
                    "score": round(h.get("score", 0), 4),
                }
                for h in hits
            ],
        }
        per_query.append(rec)
        by_cat.setdefault(q["category"], []).append(rec)

        if not args.quiet:
            mark = f"✅ rank={rank}" if hit else "❌ 未命中"
            print(f"[{q['id']}] {mark}  「{q['query']}」")
            if not hit:
                top = ", ".join(f"{t['name']}({t['subject_id']})" for t in rec["top"][:5])
                print(f"        top5: {top}")

    total = len(per_query)
    print(f"\n{'=' * 62}")
    print(f"总计  Hit Rate@{args.topk} = {hits_at}/{total} = {hits_at / total * 100:.1f}%"
          f"   MRR = {rr_sum / total:.4f}")
    print(f"{'=' * 62}")
    print(f"{'类别':<10}{'命中':<8}{'Hit Rate':<12}{'MRR':<10}说明")
    for cat, recs in sorted(by_cat.items()):
        n = len(recs)
        h = sum(1 for r in recs if r["hit"])
        mrr = sum((1.0 / r["rank"]) if r["rank"] else 0.0 for r in recs) / n
        print(f"{cat:<10}{f'{h}/{n}':<8}{f'{h / n * 100:.0f}%':<12}{mrr:<10.4f}{golden['categories'].get(cat, '')[:28]}")

    if args.json:
        args.json.write_text(
            json.dumps(
                {
                    "topk": args.topk,
                    "strategy": args.strategy or "vector",
                    "summary": {
                        "total": total,
                        "hits": hits_at,
                        "hit_rate": round(hits_at / total, 4),
                        "mrr": round(rr_sum / total, 4),
                    },
                    "by_category": {
                        c: {
                            "total": len(rs),
                            "hits": sum(1 for r in rs if r["hit"]),
                            "hit_rate": round(sum(1 for r in rs if r["hit"]) / len(rs), 4),
                            "mrr": round(
                                sum((1.0 / r["rank"]) if r["rank"] else 0.0 for r in rs) / len(rs), 4
                            ),
                        }
                        for c, rs in by_cat.items()
                    },
                    "queries": per_query,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print(f"\n结果已写入 {args.json}")

    # 刻意**不**因召回不全而返回非零：输出的是「测量结果」而不是「断言」。
    # 召回率低是待改进的基线数据，不是脚本失败；用非零退出码会让 CI 把它当成故障。
    return 0


if __name__ == "__main__":
    sys.exit(main())
