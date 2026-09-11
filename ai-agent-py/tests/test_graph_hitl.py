"""离线测试：脚本化模型 + 假 HTTP，验证状态机双 interrupt 与下单幂等。

不依赖真实 LLM 与 12306 服务：
- ScriptedModel 按剧本返回带 tool_calls 的 AIMessage；
- FakeSession 覆写 _request，路由到内置假数据并计数 purchase/pay 调用。
"""
from __future__ import annotations

from typing import Any, List, Optional

import pytest
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langgraph.types import Command

from app import graph as graph_mod
from app.graph import build_graph
from app.tools import client as client_mod
from app.tools.client import Session

STATIONS = [
    {"name": "北京南", "code": "VNP", "spell": "beijingnan", "regionName": "北京"},
    {"name": "上海虹桥", "code": "AOH", "spell": "shanghaihongqiao", "regionName": "上海"},
]

TRAIN_QUERY = {
    "trainList": [
        {
            "trainId": "2",
            "trainNumber": "G101",
            "departure": "北京南",
            "arrival": "上海虹桥",
            "departureTime": "06:36",
            "arrivalTime": "12:30",
            "duration": "05:54",
            "saleStatus": 0,
            "seatClassList": [
                {"type": 2, "quantity": 10, "price": 550.0, "candidate": False},
                {"type": 1, "quantity": 0, "price": 933.0, "candidate": False},
            ],
        }
    ]
}

PASSENGERS = [
    {"id": "1", "realName": "张三", "idType": 0, "discountType": 0, "phone": "138****5678", "verifyStatus": 1}
]

PLAN_ARGS = {
    "train_id": "2",
    "train_number": "G101",
    "departure": "北京南",
    "arrival": "上海虹桥",
    "departure_date": "2026-09-12",
    "seat_type": 2,
    "passenger_ids": ["1"],
    "passenger_names": ["张三"],
    "price_yuan_per_person": 550.0,
}


class ScriptedModel(BaseChatModel):
    """按剧本依次返回 AIMessage 的假模型；bind_tools 原样返回 self。"""

    script: List[Any]
    calls: int = 0

    @property
    def _llm_type(self) -> str:
        return "scripted"

    def _generate(self, messages: Any, stop: Any = None, run_manager: Any = None, **kwargs: Any) -> ChatResult:
        msg = self.script[min(self.calls, len(self.script) - 1)]
        self.calls += 1
        return ChatResult(generations=[ChatGeneration(message=msg)])

    def bind_tools(self, tools: Any, **kwargs: Any) -> "ScriptedModel":
        return self


class FakeSession(Session):
    """路由假 HTTP 数据，并对 purchase / pay 调用计数。"""

    def __init__(self) -> None:
        super().__init__()
        self.token = "fake-token"
        self.username = "admin"
        self.purchases = 0
        pays = 0
        self.pays = pays

    def _request(self, method, path, *, params=None, json=None, auth_required=True):  # noqa: ANN001
        if method == "GET" and path == "/api/ticket-service/station/all":
            return STATIONS
        if method == "GET" and path == "/api/ticket-service/ticket/query":
            assert params and params.get("fromStation") == "VNP"
            return TRAIN_QUERY
        if method == "GET" and path == "/api/user-service/passenger/query":
            return PASSENGERS
        if method == "POST" and path == "/api/ticket-service/ticket/purchase/v2":
            self.purchases += 1
            return {
                "orderSn": "SN1798",
                "ticketOrderDetails": [
                    {
                        "seatType": 2,
                        "carriageNumber": "05",
                        "seatNumber": "12A",
                        "realName": "张三",
                        "idType": 0,
                        "idCard": "110101199001011234",
                        "ticketType": 0,
                        "amount": 55000,
                    }
                ],
            }
        if method == "GET" and path == "/api/order-service/order/ticket/query":
            return {
                "orderSn": params.get("orderSn"),
                "trainNumber": "G101",
                "departure": "北京南",
                "arrival": "上海虹桥",
                "ridingDate": "2026-09-12",
                "departureTime": "06:36",
                "arrivalTime": "12:30",
                "passengerDetails": [
                    {
                        "realName": "张三",
                        "seatType": 2,
                        "carriageNumber": "05",
                        "seatNumber": "12A",
                        "amount": 55000,
                        "statusName": "待支付",
                    }
                ],
            }
        if method == "POST" and path == "/api/pay-service/pay/create":
            self.pays += 1
            return {"body": "<form action='sandbox'/>"}
        raise AssertionError(f"未预期的调用: {method} {path}")


def _tool_call(name: str, args: dict, cid: str) -> dict:
    return {"name": name, "args": args, "id": cid}


@pytest.fixture()
def env(monkeypatch):
    graph_mod.ledger_clear()
    session = FakeSession()
    thread_id = "t-test"
    client_mod.put_session(thread_id, session)
    yield thread_id, session
    client_mod.drop_session(thread_id)
    graph_mod.ledger_clear()


def _interrupt_of(graph, cfg) -> Optional[dict]:
    snapshot = graph.get_state(cfg)
    if not snapshot.next:
        return None
    for task in snapshot.tasks:
        for item in getattr(task, "interrupts", []) or []:
            return item.value
    return None


def _last_ai(graph, cfg) -> str:
    messages = graph.get_state(cfg).values.get("messages") or []
    for msg in reversed(messages):
        if isinstance(msg, AIMessage) and isinstance(msg.content, str) and msg.content.strip():
            return msg.content
    return ""


def _all_ai(graph, cfg) -> str:
    messages = graph.get_state(cfg).values.get("messages") or []
    return "\n".join(m.content for m in messages if isinstance(m, AIMessage) and isinstance(m.content, str))


def test_full_flow_double_interrupt_and_pay_gate_decline(env):
    thread_id, session = env
    model = ScriptedModel(
        script=[
            AIMessage(content="", tool_calls=[_tool_call("search_trains", {"from_city": "北京", "to_city": "上海", "departure_date": "2026-09-12"}, "c1")]),
            AIMessage(content="", tool_calls=[_tool_call("list_passengers", {}, "c2")]),
            AIMessage(content="", tool_calls=[_tool_call("submit_order_plan", PLAN_ARGS, "c3")]),
        ]
    )
    graph = build_graph(model=model)
    cfg = {"configurable": {"thread_id": thread_id}}

    graph.invoke({"messages": [HumanMessage(content="帮我买明天北京到上海的二等座")]}, cfg)

    # 第一道人工确认：下单计划
    pending = _interrupt_of(graph, cfg)
    assert pending and pending["type"] == "confirm_order"
    assert pending["plan"]["total_yuan"] == 550.0
    assert session.purchases == 0, "确认前不得发单"

    graph.invoke(Command(resume={"approved": True, "note": ""}), cfg)

    # 第二道人工确认：支付前
    pending = _interrupt_of(graph, cfg)
    assert pending and pending["type"] == "confirm_pay"
    assert pending["order"]["order_sn"] == "SN1798"
    assert session.purchases == 1

    graph.invoke(Command(resume={"approved": False, "note": "先不支付"}), cfg)

    assert not graph.get_state(cfg).next, "拒绝支付后应结束"
    assert session.pays == 0, "拒绝支付不得创建支付单"
    assert "待支付" in _last_ai(graph, cfg)


def test_idempotent_replay_does_not_refire_purchase(env):
    thread_id, session = env
    model = ScriptedModel(
        script=[
            AIMessage(content="", tool_calls=[_tool_call("search_trains", {"from_city": "北京", "to_city": "上海", "departure_date": "2026-09-12"}, "c1")]),
            AIMessage(content="", tool_calls=[_tool_call("list_passengers", {}, "c2")]),
            AIMessage(content="", tool_calls=[_tool_call("submit_order_plan", PLAN_ARGS, "c3")]),
            # 第一轮在支付门被拒后结束；第二轮用户重复提出同一下单意图
            AIMessage(content="", tool_calls=[_tool_call("submit_order_plan", PLAN_ARGS, "c4")]),
        ]
    )
    graph = build_graph(model=model)
    cfg = {"configurable": {"thread_id": thread_id}}

    graph.invoke({"messages": [HumanMessage(content="买 G101 二等座")]}, cfg)
    assert _interrupt_of(graph, cfg)["type"] == "confirm_order"
    graph.invoke(Command(resume={"approved": True, "note": ""}), cfg)
    assert _interrupt_of(graph, cfg)["type"] == "confirm_pay"
    graph.invoke(Command(resume={"approved": False, "note": ""}), cfg)
    assert not graph.get_state(cfg).next
    assert session.purchases == 1

    # 同一下单意图重放：确认门仍会弹（钱的事必须人点头），但发单走幂等台账
    graph.invoke({"messages": [HumanMessage(content="还是刚才那单，再帮我下一次次")]}, cfg)
    assert _interrupt_of(graph, cfg)["type"] == "confirm_order"
    graph.invoke(Command(resume={"approved": True, "note": ""}), cfg)
    assert session.purchases == 1, "幂等台账应拦截重复发单"
    assert "复用原订单" in _all_ai(graph, cfg)

    # 这次同意支付 → 创建支付单后结束
    assert _interrupt_of(graph, cfg)["type"] == "confirm_pay"
    graph.invoke(Command(resume={"approved": True, "note": ""}), cfg)
    assert not graph.get_state(cfg).next
    assert session.pays == 1
    assert "支付单已创建" in _last_ai(graph, cfg)


def test_reject_plan_routes_back_to_agent_without_purchase(env):
    thread_id, session = env
    model = ScriptedModel(
        script=[
            AIMessage(content="", tool_calls=[_tool_call("search_trains", {"from_city": "北京", "to_city": "上海", "departure_date": "2026-09-12"}, "c1")]),
            AIMessage(content="", tool_calls=[_tool_call("submit_order_plan", PLAN_ARGS, "c2")]),
            AIMessage(content="好的，已按您的意见放弃该计划，需要换一等座我重新查。"),
        ]
    )
    graph = build_graph(model=model)
    cfg = {"configurable": {"thread_id": thread_id}}

    graph.invoke({"messages": [HumanMessage(content="买 G101")]}, cfg)
    assert _interrupt_of(graph, cfg)["type"] == "confirm_order"
    graph.invoke(Command(resume={"approved": False, "note": "换一等座"}), cfg)

    assert not graph.get_state(cfg).next
    assert session.purchases == 0
    assert "放弃该计划" in _last_ai(graph, cfg)
