#!/usr/bin/env python3
"""对话级验收：**模型会不会在正确时机调对工具**。

为什么需要这一步（离评估做不到的事）：
`run_eval.py` 只验证「给定查询，检索能不能召回」。它完全不涉及：
- 模型是否会在「想不起名字」时主动调 `search_knowledge`
- 会不会在「已知作品名」时错误地调它（本该用结构化检索）
- 会不会对完全无关的问题乱调工具
- 工具失败/空结果时模型会不会编造

用法：
    python3 eval/check_tool_routing.py
    python3 eval/check_tool_routing.py --only 1,3

⚠️ 会真实调用对话模型（每次约几秒、消耗少量 token）。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.request
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
REPO_ROOT = EVAL_DIR.parent
CHAT_URL = os.environ.get("CHAT_URL", "http://127.0.0.1:8013/internal/ai/chat")

# (编号, 用户问题, 期望：必须调用的工具 / 禁止调用的工具, 说明)
CASES = [
    (
        "1",
        "我很久以前看过一个番，主角是个红发剑士，结局特别虐，实在想不起名字了，能帮我找找吗",
        {"expect_any": ["search_knowledge"], "forbid": []},
        "典型的「只记得剧情」——这正是 search_knowledge 存在的理由",
    ),
    (
        "2",
        "最近这一季有什么高分番推荐",
        {"expect_any": ["browse_anime"], "forbid": []},
        "榜单类查询应该走结构化接口，不该走向量检索",
    ),
    (
        "3",
        "命运石之门这部番评分多少",
        {"expect_any": ["search_anime"], "forbid": ["search_knowledge"]},
        "已知作品名+问精确数值 → 结构化检索；用向量检索回答精确数值是错的",
    ),
    (
        "4",
        "今天北京天气怎么样",
        {"expect_any": [], "forbid": ["search_knowledge", "browse_anime", "search_anime"]},
        "完全越界的问题应被话题守卫拒绝，且不该采样任何番剧工具",
    ),
]


def load_token() -> str:
    if os.environ.get("AI_INTERNAL_TOKEN"):
        return os.environ["AI_INTERNAL_TOKEN"]
    for line in (REPO_ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if line.strip().startswith("AI_INTERNAL_TOKEN="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


def chat(question: str, token: str) -> dict:
    """返回 {tools: [工具名...], text: 回答全文, finish: 结束原因, error: 错误}"""
    body = json.dumps({"message": question}).encode("utf-8")
    req = urllib.request.Request(
        CHAT_URL,
        data=body,
        headers={"Content-Type": "application/json", "X-Internal-Token": token},
    )
    tools, text, finish, error = [], [], None, None
    with urllib.request.urlopen(req, timeout=180) as resp:
        event = None
        for raw in resp:
            line = raw.decode("utf-8", "ignore").strip()
            if not line:
                continue
            if line.startswith("event:"):
                event = line[6:].strip()
                continue
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            try:
                data = json.loads(payload) if payload else {}
            except json.JSONDecodeError:
                continue
            if event == "tool-call":
                name = data.get("name") or (data.get("tool") or {}).get("name")
                if name:
                    tools.append(name)
            elif event == "text-delta":
                text.append(data.get("text") or data.get("delta") or "")
            elif event == "done":
                finish = data.get("finishReason") or data.get("finish")
            elif event == "error":
                error = data.get("message") or str(data)
    return {"tools": tools, "text": "".join(text), "finish": finish, "error": error}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default=None, help="只跑指定编号，如 1,3")
    args = ap.parse_args()

    token = load_token()
    cases = [c for c in CASES if args.only is None or c[0] in args.only.split(",")]

    passed = failed = 0
    for num, question, rule, why in cases:
        try:
            result = chat(question, token)
        except Exception as exc:  # noqa: BLE001
            print(f"\n[{num}] ❌ 请求失败: {exc}")
            failed += 1
            continue

        tools = result["tools"]
        ok = True
        reasons = []

        expect_any = rule["expect_any"]
        if expect_any and not (set(tools) & set(expect_any)):
            ok = False
            reasons.append(f"期望调用 {expect_any} 之一，实际 {tools or '无'}")

        forbidden = set(tools) & set(rule["forbid"])
        if forbidden:
            ok = False
            reasons.append(f"不该调用 {sorted(forbidden)}")

        if result["error"]:
            ok = False
            reasons.append(f"返回 error 事件: {result['error']}")

        if ok:
            passed += 1
            print(f"\n[{num}] ✅ 调用工具: {tools or '无'}")
        else:
            failed += 1
            print(f"\n[{num}] ❌ 调用工具: {tools or '无'}")
            for reason in reasons:
                print(f"      → {reason}")

        print(f"      问题: {question}")
        print(f"      意图: {why}")
        print(f"      结束: {result['finish']}  回答长度: {len(result['text'])}")
        snippet = result["text"].replace("\n", " ")[:120]
        print(f"      回答: {snippet}")

    print(f"\n结果：{passed} 通过 / {failed} 失败")
    print("注意：工具选择对不对是「行为」验收；回答质量的评分仍需人工判断。")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
