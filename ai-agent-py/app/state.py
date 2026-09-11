"""LangGraph 状态定义。"""
from __future__ import annotations

from typing import Annotated, Any, Dict, List, Optional, TypedDict

from langgraph.graph.message import add_messages


class TicketAgentState(TypedDict, total=False):
    """对话式购票 Agent 的全局状态。

    messages: 完整对话历史（Human/AI/Tool/System），add_messages 归约。
    order_plan: 待确认/已登记的下单计划（submit_order_plan 产出）。
    idempotency_key: 与 order_plan 绑定的幂等键，重试/重放复用。
    plan_submitted: 本轮是否刚登记了新计划（用于 tools 后的路由）。
    plan_decision: 用户对下单计划的确认结果（True 下单 / False 放弃或修改）。
    order_result: 下单成功结果（order_sn、总价、座位明细）。
    order_error: 下单失败原因（回 agent 节点让 LLM 解释或改方案）。
    pay_decision: 用户对「支付前确认」的答复（True 发起支付单 / False 保留待支付）。
    pay_result: 支付创单结果。
    """

    messages: Annotated[List[Any], add_messages]
    order_plan: Optional[Dict[str, Any]]
    idempotency_key: Optional[str]
    plan_submitted: bool
    plan_decision: Optional[bool]
    order_result: Optional[Dict[str, Any]]
    order_error: Optional[str]
    pay_decision: Optional[bool]
    pay_result: Optional[Dict[str, Any]]
