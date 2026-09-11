"""LangGraph 状态机：对话式购票（场景一）。

拓扑：
    START → agent ⇄ tools ─(登记下单计划)→ confirm_order ─(interrupt 人工确认)─┐
              ↑                                  │ 同意                        │ 拒绝
              └──────────────────────────────────┼────────────────────────────┘
                                                 ↓
                                            place_order（幂等台账）
                                                 │ 失败 → agent
                                                 ↓ 成功
                                            pay_gate ─(interrupt 支付前人工确认)─┐
                                                 │ 同意                        │ 拒绝
                                                 ↓                             ↓
                                           initiate_pay                       END
                                                 ↓
                                                END

两个人工确认点均用 langgraph.types.interrupt 实现（HITL），配合 checkpointer 挂起/恢复；
下单幂等 = 客户端 idempotency_key 台账（同意图不重复发单）+ 服务端 @Idempotent 用户锁兜底。
"""
from __future__ import annotations

import json
import threading
from datetime import date
from typing import Any, Callable, Dict, Optional

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt

from app.llm import get_model
from app.state import TicketAgentState
from app.tools import TOOL_FUNCS, create_pay_order
from app.tools.client import (
    ToolCallError,
    get_session,
    reset_current_session,
    set_current_session,
)
from app.tools.order_tools import purchase_tickets

SYSTEM_PROMPT = """你是 12306 对话式购票助手，运行在 LangGraph 状态机上，通过工具完成真实查询与下单。

工作流程约定：
1. 信息不全（出发地/目的地/日期/席别/乘车人）时先问用户，不要臆测；用户说「明天」等相对日期时，参考今日日期 {today} 换算为 yyyy-MM-dd。
2. 查票用 search_trains（城市名即可，系统自动解析站码）；下单前必须 list_passengers 拿到真实乘车人 id。
3. 具备下单条件时调用 submit_order_plan 登记计划：train_id、seat_type、price_yuan_per_person 必须逐字来自 search_trains 结果，passenger_ids 必须来自 list_passengers 结果。登记后系统会向用户弹出人工确认，你不得声称已经下单。
4. 用户确认同意后系统自动执行下单并再次弹出「支付前确认」；支付必须由用户人工确认，你不得自动扣款、不得声称已支付。
5. 工具报错时向用户解释原因并给替代方案（换车次/换席别/换日期/稍后重试）。
6. 金额一律以元为单位向用户展示；禁止凭记忆报票价、禁止编造任何 id。
7. 回复用简洁中文，关键信息（车次、时刻、席别、余票、票价、订单号）结构化列出。"""

# ---------- 客户端幂等台账：idempotency_key -> order_result ----------
_LEDGER: Dict[str, Dict[str, Any]] = {}
_LEDGER_LOCK = threading.Lock()


def ledger_get(key: str) -> Optional[Dict[str, Any]]:
    with _LEDGER_LOCK:
        return _LEDGER.get(key)


def ledger_put(key: str, order_result: Dict[str, Any]) -> None:
    with _LEDGER_LOCK:
        _LEDGER[key] = order_result


def ledger_clear() -> None:
    with _LEDGER_LOCK:
        _LEDGER.clear()


def _dump(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, default=str)


def _thread_id(config: RunnableConfig) -> str:
    return (config or {}).get("configurable", {}).get("thread_id", "") or ""


# ---------- 节点 ----------
def agent_node(state: TicketAgentState, config: RunnableConfig, model: Any = None) -> Dict[str, Any]:
    model = model or get_model()
    system = SystemMessage(content=SYSTEM_PROMPT.format(today=date.today().isoformat()))
    try:
        reply = model.bind_tools(TOOL_FUNCS).invoke([system, *state["messages"]])
    except Exception as exc:  # 缺 Key / 网络异常时降级为可读提示，避免裸 500
        reply = AIMessage(
            content=f"抱歉，模型调用失败：{type(exc).__name__}: {exc}。"
            f"请检查 AI_LLM_API_KEY 与网络后重试，您的对话上下文已保留。"
        )
    return {"messages": [reply]}


def make_tools_node() -> Callable[[TicketAgentState, Any], Dict[str, Any]]:
    registry: Dict[str, Callable[..., Any]] = {fn.__name__: fn for fn in TOOL_FUNCS}

    def tools_node(state: TicketAgentState, config: RunnableConfig) -> Dict[str, Any]:
        session = get_session(_thread_id(config))
        token = set_current_session(session)
        last = state["messages"][-1]
        out_messages = []
        patches: Dict[str, Any] = {"plan_submitted": False}
        try:
            for call in getattr(last, "tool_calls", []) or []:
                name = call.get("name")
                args = call.get("args") or {}
                fn = registry.get(name)
                if fn is None:
                    out_messages.append(
                        ToolMessage(content=f"错误：未知工具 {name}", tool_call_id=call["id"], name=name)
                    )
                    continue
                try:
                    result = fn(**args)
                except ToolCallError as exc:
                    out_messages.append(
                        ToolMessage(content=f"错误：{exc}", tool_call_id=call["id"], name=name)
                    )
                    continue
                except TypeError as exc:
                    out_messages.append(
                        ToolMessage(content=f"错误：工具参数不匹配({exc})", tool_call_id=call["id"], name=name)
                    )
                    continue
                if name == "submit_order_plan":
                    patches["order_plan"] = result
                    patches["idempotency_key"] = result.get("idempotency_key")
                    patches["plan_submitted"] = True
                    patches["plan_decision"] = None
                    content = f"下单计划已登记，等待用户人工确认：{_dump(result)}"
                else:
                    content = _dump(result)
                out_messages.append(ToolMessage(content=content, tool_call_id=call["id"], name=name))
        finally:
            reset_current_session(token)
        return {"messages": out_messages, **patches}

    return tools_node


def confirm_order_node(state: TicketAgentState, config: RunnableConfig) -> Dict[str, Any]:
    plan = state.get("order_plan") or {}
    key = state.get("idempotency_key") or "na"
    names = "、".join(p.get("name", "?") for p in plan.get("passengers", []))
    summary = (
        f"下单计划已生成，请确认：\n"
        f"- 车次：{plan.get('train_number')}（{plan.get('departure')} → {plan.get('arrival')}）\n"
        f"- 日期：{plan.get('departure_date')}\n"
        f"- 席别：{plan.get('seat_name')}，单价 {plan.get('price_yuan_per_person')} 元\n"
        f"- 乘车人：{names}\n"
        f"- 总价：{plan.get('total_yuan')} 元\n"
        f"确认无误请回复「确认」，需要调整请直接告诉我改哪里。"
    )
    prompt = AIMessage(content=summary, id=f"confirm_order_prompt:{key}")
    decision = interrupt({"type": "confirm_order", "plan": plan})
    approved, note = _read_decision(decision)
    feedback = HumanMessage(
        content=f"[人工确认-下单] {'同意' if approved else '拒绝'}" + (f"，意见：{note}" if note else ""),
        id=f"confirm_order_decision:{key}",
    )
    return {"messages": [prompt, feedback], "plan_decision": approved, "plan_submitted": False}


def place_order_node(state: TicketAgentState, config: RunnableConfig) -> Dict[str, Any]:
    plan = state.get("order_plan") or {}
    key = state.get("idempotency_key") or "na"
    session = get_session(_thread_id(config))

    reused = False
    result = ledger_get(key)
    if result is None:
        try:
            result = purchase_tickets(session, plan)
        except ToolCallError as exc:
            msg = AIMessage(
                content=f"下单失败：{exc}。您可以让我换车次/席别/日期重试，或稍后再试。",
                id=f"place_order_error:{key}",
            )
            return {"messages": [msg], "order_error": str(exc), "order_result": None}
        ledger_put(key, result)
    else:
        reused = True

    lines = [f"下单成功，订单号：{result.get('order_sn')}"]
    if reused:
        lines.append("（检测到同一下单意图已执行过，直接复用原订单，未重复发单）")
    for detail in result.get("details", []):
        lines.append(
            f"- {detail.get('real_name')} {detail.get('seat_name')} "
            f"{detail.get('carriage_number')} 车 {detail.get('seat_number')}，{detail.get('amount_yuan')} 元"
        )
    lines.append(f"合计：{result.get('total_yuan')} 元")
    msg = AIMessage(content="\n".join(lines), id=f"place_order_result:{key}:{result.get('order_sn')}")
    return {"messages": [msg], "order_result": result, "order_error": None}


def pay_gate_node(state: TicketAgentState, config: RunnableConfig) -> Dict[str, Any]:
    order = state.get("order_result") or {}
    order_sn = order.get("order_sn") or "na"
    prompt = AIMessage(
        content=(
            f"订单 {order_sn} 已创建，当前状态为待支付，合计 {order.get('total_yuan')} 元。\n"
            f"是否现在发起支付？支付属于资金操作，必须经您确认我才执行；拒绝则订单保留为待支付。"
        ),
        id=f"pay_gate_prompt:{order_sn}",
    )
    decision = interrupt({"type": "confirm_pay", "order": order})
    approved, note = _read_decision(decision)
    feedback = HumanMessage(
        content=f"[人工确认-支付] {'同意' if approved else '拒绝'}" + (f"，意见：{note}" if note else ""),
        id=f"pay_gate_decision:{order_sn}",
    )
    messages = [prompt, feedback]
    if not approved:
        messages.append(
            AIMessage(
                content=f"好的，订单 {order_sn} 保留为待支付状态。您可以在控制台订单页完成支付，"
                f"或稍后让我查询订单/支付状态；未支付订单超时会自动关闭并释放座位。",
                id=f"pay_gate_declined:{order_sn}",
            )
        )
    return {"messages": messages, "pay_decision": approved}


def initiate_pay_node(state: TicketAgentState, config: RunnableConfig) -> Dict[str, Any]:
    order = state.get("order_result") or {}
    order_sn = order.get("order_sn") or "na"
    session = get_session(_thread_id(config))
    try:
        pay = create_pay_order(
            session,
            order_sn,
            float(order.get("total_yuan") or 0.0),
            subject=f"{order.get('departure', '')}-{order.get('arrival', '')}",
        )
    except ToolCallError as exc:
        return {
            "messages": [
                AIMessage(content=f"支付单创建失败：{exc}。订单仍为待支付，可稍后重试。", id=f"pay_error:{order_sn}")
            ],
            "pay_result": None,
        }
    msg = AIMessage(
        content=(
            f"支付单已创建（订单 {order_sn}）。最终资金动作由您在支付宝沙箱或控制台订单页完成，"
            f"我不会代您扣款；完成后可以让我查询支付状态确认结果。"
        ),
        id=f"pay_created:{order_sn}",
    )
    return {"messages": [msg], "pay_result": pay}


def _read_decision(decision: Any) -> tuple[bool, str]:
    if isinstance(decision, dict):
        return bool(decision.get("approved")), str(decision.get("note") or "")
    return bool(decision), ""


# ---------- 路由 ----------
def route_after_agent(state: TicketAgentState) -> str:
    last = state["messages"][-1]
    return "tools" if getattr(last, "tool_calls", None) else END


def route_after_tools(state: TicketAgentState) -> str:
    # 新登记的计划且尚未被人工裁决 → 进确认门；tools_node 每次 submit 都会把 plan_decision 重置为 None
    if state.get("plan_submitted") and state.get("plan_decision") is None:
        return "confirm_order"
    return "agent"


def route_after_confirm(state: TicketAgentState) -> str:
    return "place_order" if state.get("plan_decision") else "agent"


def route_after_place(state: TicketAgentState) -> str:
    return "agent" if state.get("order_error") else "pay_gate"


def route_after_pay_gate(state: TicketAgentState) -> str:
    return "initiate_pay" if state.get("pay_decision") else END


# ---------- 构建 ----------
def build_graph(model: Any = None, checkpointer: Any = None):
    """编译购票 Agent 图。model/checkpointer 可注入（测试用脚本模型与内存检查点）。"""
    bound_model = model or get_model()

    def _agent(state: TicketAgentState, config: RunnableConfig) -> Dict[str, Any]:
        return agent_node(state, config, model=bound_model)

    graph = StateGraph(TicketAgentState)
    graph.add_node("agent", _agent)
    graph.add_node("tools", make_tools_node())
    graph.add_node("confirm_order", confirm_order_node)
    graph.add_node("place_order", place_order_node)
    graph.add_node("pay_gate", pay_gate_node)
    graph.add_node("initiate_pay", initiate_pay_node)

    graph.add_edge(START, "agent")
    graph.add_conditional_edges("agent", route_after_agent, {"tools": "tools", END: END})
    graph.add_conditional_edges("tools", route_after_tools, {"confirm_order": "confirm_order", "agent": "agent"})
    graph.add_conditional_edges("confirm_order", route_after_confirm, {"place_order": "place_order", "agent": "agent"})
    graph.add_conditional_edges("place_order", route_after_place, {"pay_gate": "pay_gate", "agent": "agent"})
    graph.add_conditional_edges("pay_gate", route_after_pay_gate, {"initiate_pay": "initiate_pay", END: END})
    graph.add_edge("initiate_pay", END)

    return graph.compile(checkpointer=checkpointer or MemorySaver())
