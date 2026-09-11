"""交互式命令行演示：python -m app.cli

流程：登录 → 多轮对话 → 遇到人工确认 interrupt 时终端询问 y/n → resume 恢复。
"""
from __future__ import annotations

import argparse
import getpass
import json
import uuid

from langchain_core.messages import HumanMessage
from langgraph.types import Command

from app.config import settings
from app.graph import build_graph
from app.tools.client import ToolCallError, get_session


def _render_pending(pending: dict) -> None:
    payload = pending.get("payload") or {}
    kind = pending.get("type")
    print("\n" + "=" * 62)
    if kind == "confirm_order":
        plan = payload.get("plan") or {}
        print("【人工确认 · 下单】")
        print(f"  车次 {plan.get('train_number')}  {plan.get('departure')} → {plan.get('arrival')}  {plan.get('departure_date')}")
        print(f"  席别 {plan.get('seat_name')}  单价 {plan.get('price_yuan_per_person')} 元")
        print("  乘车人 " + "、".join(p.get("name", "?") for p in plan.get("passengers", [])))
        print(f"  总价 {plan.get('total_yuan')} 元   幂等键 {plan.get('idempotency_key')}")
    elif kind == "confirm_pay":
        order = payload.get("order") or {}
        print("【人工确认 · 支付前】")
        print(f"  订单号 {order.get('order_sn')}  合计 {order.get('total_yuan')} 元")
    else:
        print(f"【人工确认 · {kind}】")
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    print("=" * 62)


def main() -> None:
    parser = argparse.ArgumentParser(description="12306 对话式购票 Agent CLI")
    parser.add_argument("--username", default=settings.demo_username)
    parser.add_argument("--password", default=settings.demo_password)
    parser.add_argument("--base-url", default=settings.base_12306_url)
    args = parser.parse_args()

    username = args.username or input("12306 用户名: ").strip()
    password = args.password or getpass.getpass("密码: ")

    thread_id = uuid.uuid4().hex
    session = get_session(thread_id)
    session.base_url = args.base_url.rstrip("/")
    try:
        session.login(username, password)
    except ToolCallError as exc:
        print(f"登录失败：{exc}")
        return
    print(f"已登录：{session.real_name or session.username}（thread={thread_id[:8]}…）")

    graph = build_graph()
    config = {"configurable": {"thread_id": thread_id}}
    print("输入购票需求开始对话；quit 退出。\n")

    while True:
        snapshot = graph.get_state(config)
        if snapshot.next:
            pending = None
            for task in snapshot.tasks:
                for item in getattr(task, "interrupts", []) or []:
                    pending = item.value
            _render_pending({"type": (pending or {}).get("type"), "payload": pending})
            answer = input("确认执行？(y=同意 / 其他输入视为拒绝并作为意见回传): ").strip()
            approved = answer.lower() in {"y", "yes", "确认", "同意"}
            note = "" if approved else answer
            graph.invoke(Command(resume={"approved": approved, "note": note}), config)
        else:
            text = input("您: ").strip()
            if not text:
                continue
            if text.lower() in {"quit", "exit", "退出"}:
                break
            graph.invoke({"messages": [HumanMessage(content=text)]}, config)

        snapshot = graph.get_state(config)
        messages = snapshot.values.get("messages") or []
        for msg in reversed(messages):
            if type(msg).__name__ == "AIMessage" and isinstance(msg.content, str) and msg.content.strip():
                print(f"\n助手: {msg.content}\n")
                break


if __name__ == "__main__":
    main()
