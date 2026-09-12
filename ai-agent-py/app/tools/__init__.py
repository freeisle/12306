"""工具层：对 12306 各微服务的受控封装（供 LangGraph Tool Calling 使用）。"""
from app.tools.order_tools import (
    create_pay_order,
    list_passengers,
    query_order,
    submit_order_plan,
)
from app.tools.ticket_tools import SEAT_TYPE_NAMES, search_trains
from app.tools.waitlist_tools import recommend_ticket_options

TOOL_FUNCS = [
    search_trains,
    recommend_ticket_options,
    list_passengers,
    submit_order_plan,
    query_order,
]

__all__ = [
    "TOOL_FUNCS",
    "SEAT_TYPE_NAMES",
    "search_trains",
    "recommend_ticket_options",
    "list_passengers",
    "submit_order_plan",
    "query_order",
    "create_pay_order",
]
