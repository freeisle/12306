"""订单/支付域工具。

契约：
- 乘车人 GET /api/user-service/passenger/query（需 Authorization）→ [{id, realName, idType, idCard, discountType, phone, verifyStatus}]
- 下单入口 POST /api/ticket-service/ticket/purchase/v2（需 Authorization，ticket-service 锁座后 Feign 建单）
  请求 {trainId, departure, arrival, passengers:[{passengerId, seatType}], chooseSeats:[]}
  chooseSeats 传空数组表示不选座、由服务端自动分配；只有明确选座时才传非空值，
  且元素必须是合法座位串（如二等座 "A1"），传 [""] 会让 ticket-service 解析 substring(1) 抛 StringIndexOutOfBoundsException。
  响应 data.orderSn + ticketOrderDetails[{seatType, carriageNumber, seatNumber, realName, amount(分)}]
  服务端幂等：@Idempotent(SPEL) 按 username 非阻塞锁，并发重复下单被拒（"正在执行下单流程，请稍后..."）；
  客户端幂等：本模块以 idempotency_key 做台账，同一意图重试/重放不重复发单。
- 订单查询 GET /api/order-service/order/ticket/query?orderSn=（需 Authorization），金额单位分。
- 支付创单 POST /api/pay-service/pay/create {channel:0, tradeType:0, orderSn, totalAmount(元), subject}
  → data.body 为支付宝沙箱自动提交表单；无 mock 成功回调，资金动作必须由人在沙箱/控制台完成。
"""
from __future__ import annotations

import hashlib
from typing import Any, Dict, List

from app.tools.client import Session, ToolCallError, current_session
from app.tools.ticket_tools import seat_name

DISCOUNT_TYPE_NAMES = {0: "成人", 1: "儿童", 2: "学生"}


def list_passengers() -> List[Dict[str, Any]]:
    """查询当前登录用户名下的常用乘车人列表。

    Returns:
        乘车人精简列表：passenger_id、name、id_type、discount_type 名称、phone 掩码、verify_status。
    """
    session = current_session()
    data = session.get("/api/user-service/passenger/query")
    result: List[Dict[str, Any]] = []
    for item in data or []:
        result.append(
            {
                "passenger_id": str(item.get("id")),
                "name": item.get("realName"),
                "id_type": item.get("idType"),
                "discount_type": DISCOUNT_TYPE_NAMES.get(item.get("discountType"), "成人"),
                "phone": item.get("phone"),
                "verify_status": item.get("verifyStatus"),
            }
        )
    return result


def build_idempotency_key(
    thread_id: str,
    train_id: str,
    seat_type: int,
    passenger_ids: List[str],
    departure_date: str,
) -> str:
    """同一对话线程内「同车次+同席别+同乘车人+同日期」视为同一下单意图。"""
    raw = "|".join(
        [
            thread_id or "-",
            str(train_id),
            str(seat_type),
            "-".join(sorted(str(p) for p in passenger_ids)),
            departure_date,
        ]
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]


def submit_order_plan(
    train_id: str,
    train_number: str,
    departure: str,
    arrival: str,
    departure_date: str,
    seat_type: int,
    passenger_ids: List[str],
    passenger_names: List[str],
    price_yuan_per_person: float,
) -> Dict[str, Any]:
    """登记一条待确认的下单计划（不会真正下单，需用户人工确认后才执行）。

    Args:
        train_id: 来自 search_trains 结果的 train_id，禁止臆造。
        train_number: 车次号，如 G101。
        departure: 出发站名，与 search_trains 结果一致。
        arrival: 到达站名，与 search_trains 结果一致。
        departure_date: 出发日期 yyyy-MM-dd。
        seat_type: 席别码（0 商务座 1 一等座 2 二等座 ... 13 无座）。
        passenger_ids: 乘车人 id 列表，必须来自 list_passengers 结果。
        passenger_names: 与 passenger_ids 一一对应的姓名。
        price_yuan_per_person: 每人票价（元），来自 search_trains 的 seats.price_yuan。

    Returns:
        下单计划字典，含 idempotency_key 与总价，供确认环节展示。
    """
    if not passenger_ids:
        raise ToolCallError("下单计划缺少乘车人，请先调用 list_passengers 并由用户指定")
    session = current_session()
    plan: Dict[str, Any] = {
        "train_id": str(train_id),
        "train_number": train_number,
        "departure": departure,
        "arrival": arrival,
        "departure_date": departure_date,
        "seat_type": int(seat_type),
        "seat_name": seat_name(seat_type),
        "passengers": [
            {"passenger_id": str(pid), "name": name}
            for pid, name in zip(passenger_ids, passenger_names or [])
        ],
        "price_yuan_per_person": float(price_yuan_per_person),
        "total_yuan": round(float(price_yuan_per_person) * len(passenger_ids), 2),
    }
    plan["idempotency_key"] = build_idempotency_key(
        getattr(session, "thread_id", "") or "",
        plan["train_id"],
        plan["seat_type"],
        [p["passenger_id"] for p in plan["passengers"]],
        departure_date,
    )
    return plan


def purchase_tickets(session: Session, plan: Dict[str, Any]) -> Dict[str, Any]:
    """真正调用 purchase/v2 下单（仅由图的 place_order 节点在人工确认后调用）。"""
    session.require_login()
    payload = {
        "trainId": plan["train_id"],
        "departure": plan["departure"],
        "arrival": plan["arrival"],
        "passengers": [
            {"passengerId": p["passenger_id"], "seatType": plan["seat_type"]}
            for p in plan["passengers"]
        ],
        "chooseSeats": [],
    }
    data = session.post("/api/ticket-service/ticket/purchase/v2", json=payload)
    details = (data or {}).get("ticketOrderDetails") or []
    total_fen = sum(int(d.get("amount") or 0) for d in details)
    return {
        "order_sn": (data or {}).get("orderSn"),
        "total_yuan": round(total_fen / 100.0, 2),
        "details": [
            {
                "real_name": d.get("realName"),
                "seat_name": seat_name(d.get("seatType")),
                "carriage_number": d.get("carriageNumber"),
                "seat_number": d.get("seatNumber"),
                "amount_yuan": round(int(d.get("amount") or 0) / 100.0, 2),
            }
            for d in details
        ],
    }


def query_order(order_sn: str) -> Dict[str, Any]:
    """按订单号查询订单详情（状态、乘车人、金额）。

    Args:
        order_sn: 订单号，如下单响应或用户提供的 SN。

    Returns:
        订单精简信息：order_sn、车次、日期区间、状态名、乘车人座位与每人票价(元)、总价(元)。
    """
    session = current_session()
    data = session.get("/api/order-service/order/ticket/query", params={"orderSn": order_sn})
    if not data:
        raise ToolCallError(f"未查询到订单 {order_sn}")
    passengers = [
        {
            "real_name": p.get("realName"),
            "seat_name": seat_name(p.get("seatType")),
            "carriage_number": p.get("carriageNumber"),
            "seat_number": p.get("seatNumber"),
            "status_name": p.get("statusName"),
            "amount_yuan": round(int(p.get("amount") or 0) / 100.0, 2),
        }
        for p in data.get("passengerDetails") or []
    ]
    total_fen = sum(int(p.get("amount") or 0) for p in data.get("passengerDetails") or [])
    return {
        "order_sn": data.get("orderSn"),
        "train_number": data.get("trainNumber"),
        "departure": data.get("departure"),
        "arrival": data.get("arrival"),
        "riding_date": data.get("ridingDate"),
        "departure_time": data.get("departureTime"),
        "arrival_time": data.get("arrivalTime"),
        "status_name": _first_status_name(data),
        "passengers": passengers,
        "total_yuan": round(total_fen / 100.0, 2),
    }


def _first_status_name(data: Dict[str, Any]) -> str:
    details = data.get("passengerDetails") or []
    return details[0].get("statusName") if details else ""


def create_pay_order(session: Session, order_sn: str, total_yuan: float, subject: str) -> Dict[str, Any]:
    """创建支付单（支付宝沙箱渠道），返回沙箱表单指引。资金动作仍需人工在沙箱完成。"""
    session.require_login()
    payload = {
        "channel": 0,  # ALI_PAY
        "tradeType": 0,  # NATIVE
        "orderSn": order_sn,
        "totalAmount": float(total_yuan),
        "subject": subject,
        "outOrderSn": None,
    }
    data = session.post("/api/pay-service/pay/create", json=payload)
    return {"order_sn": order_sn, "sandbox_form_available": bool((data or {}).get("body"))}
