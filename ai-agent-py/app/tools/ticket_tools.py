"""车票域工具：查余票（含城市→站码解析）。

契约（ticket-service）：
- GET /api/ticket-service/ticket/query?fromStation=<码>&toStation=<码>&departureDate=yyyy-MM-dd
  → data.trainList[]: trainId, trainNumber, departure, arrival, departureTime(HH:mm),
    arrivalTime(HH:mm), duration, saleStatus, seatClassList[{type, quantity, price(元), candidate}]
- GET /api/ticket-service/station/all → [{name, code, spell, regionName}]
- 余票约定：quantity 为整数，0 即无票；price 单位为元。
- 席别码 VehicleSeatTypeEnum：0 商务座 1 一等座 2 二等座 3 二等包座 4 一等卧 5 二等卧
  6 软卧 7 硬卧 8 硬座 9 高级软卧 10 动卧 11 软座 12 特等座 13 无座 14 其他。
"""
from __future__ import annotations

from typing import Any, Dict, List

from app.tools.client import ToolCallError, current_session

SEAT_TYPE_NAMES: Dict[int, str] = {
    0: "商务座",
    1: "一等座",
    2: "二等座",
    3: "二等包座",
    4: "一等卧",
    5: "二等卧",
    6: "软卧",
    7: "硬卧",
    8: "硬座",
    9: "高级软卧",
    10: "动卧",
    11: "软座",
    12: "特等座",
    13: "无座",
    14: "其他",
}


def seat_name(seat_type: int) -> str:
    return SEAT_TYPE_NAMES.get(int(seat_type), f"未知席别({seat_type})")


def _compact_train(train: Dict[str, Any]) -> Dict[str, Any]:
    seats: List[Dict[str, Any]] = []
    for seat in train.get("seatClassList") or []:
        seats.append(
            {
                "seat_type": seat.get("type"),
                "seat_name": seat_name(seat.get("type")),
                "quantity": seat.get("quantity"),
                "price_yuan": float(seat.get("price") or 0.0),
                "candidate_allowed": bool(seat.get("candidate")),
            }
        )
    return {
        "train_id": str(train.get("trainId")),
        "train_number": train.get("trainNumber"),
        "departure": train.get("departure"),
        "arrival": train.get("arrival"),
        "departure_time": train.get("departureTime"),
        "arrival_time": train.get("arrivalTime"),
        "duration": train.get("duration"),
        "sale_status": train.get("saleStatus"),
        "seats": seats,
    }


def search_trains(
    from_city: str,
    to_city: str,
    departure_date: str,
    max_results: int = 8,
) -> Dict[str, Any]:
    """查询两城之间某日的余票车次列表。

    Args:
        from_city: 出发城市或站名，如「北京」「北京南」。
        to_city: 到达城市或站名，如「上海」「上海虹桥」。
        departure_date: 出发日期，格式 yyyy-MM-dd。
        max_results: 最多返回车次数，默认 8。

    Returns:
        含 from_station/to_station 站名站码与 trains 精简列表的字典；
        trains 每项含 train_id、train_number、起止站与时刻、历时、seats 余票与票价(元)。
    """
    session = current_session()
    from_name, from_code = session.resolve_station_code(from_city)
    to_name, to_code = session.resolve_station_code(to_city)
    if from_code == to_code:
        raise ToolCallError("出发地与目的地相同，请重新确认行程")
    data = session.get(
        "/api/ticket-service/ticket/query",
        params={"fromStation": from_code, "toStation": to_code, "departureDate": departure_date},
        auth_required=False,
    )
    train_list = (data or {}).get("trainList") or []
    trains = [_compact_train(t) for t in train_list[: max(1, int(max_results))]]
    return {
        "from_station": from_name,
        "from_code": from_code,
        "to_station": to_name,
        "to_code": to_code,
        "departure_date": departure_date,
        "total": len(train_list),
        "trains": trains,
    }
