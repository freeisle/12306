"""候补购票智能推荐工具（场景三）。

职责边界（方案C 的本期务实形态）：
    · 确定性打分算法在 app/planning/waitlist_scorer.py（纯函数，不经 LLM）；
    · 本工具只做「取数（search_trains，经网关调 ticket-service）+ 调算法 + 结构化返回」；
    · LLM 负责把用户模糊偏好翻译成本工具的 preferences 入参，并把返回结果讲成人话。

无票/候补/改乘决策场景应优先调用本工具，而不是只调 search_trains 后自行判断。
"""
from __future__ import annotations

from typing import Any, Dict, Optional

from app.planning.waitlist_scorer import analyze
from app.tools.ticket_tools import search_trains


def recommend_ticket_options(
    from_city: str,
    to_city: str,
    departure_date: str,
    avoid_night_arrival: bool = False,
    prefer_high_speed: bool = False,
    price_sensitive: bool = False,
    preferred_seat_type: Optional[int] = None,
    max_extra_minutes: Optional[int] = None,
    max_results: int = 10,
) -> Dict[str, Any]:
    """分析某线路某日的余票，给出「候补 vs 改乘有票」的智能推荐（含候补成功率估算）。

    适用：用户想要的车次/席别无票、纠结该候补还是改乘、热门线路票紧张等场景。

    Args:
        from_city: 出发城市或站名，如「北京」「北京南」。
        to_city: 到达城市或站名，如「杭州」「杭州东」。
        departure_date: 出发日期，格式 yyyy-MM-dd。
        avoid_night_arrival: 用户不想半夜（22:00-06:00）到达时置 True。
        prefer_high_speed: 用户优先高铁/动车时置 True。
        price_sensitive: 用户对价格敏感、优先低价时置 True。
        preferred_seat_type: 用户明确偏好的席别码（0商务座 1一等座 2二等座 4一等卧 6软卧 7硬卧 8硬座 等）。
        max_extra_minutes: 相对最早可达车次，用户最多能接受晚到多少分钟。
        max_results: 参与分析的最多车次数，默认 10。

    Returns:
        结构化推荐：buy_now(有票可下单)/low_stock(余票紧张)/waitlist(售罄可候补，含
        success_pct 百分比、band 档位、factors 影响因子)/unavailable，以及顶层
        recommendation(headline 一句话结论、primary 首选方案、reason 理由) 与
        disclaimer(估算声明)。金额单位为元。
    """
    search = search_trains(from_city, to_city, departure_date, max_results=max_results)
    trains = search.get("trains") or []
    preferences = {
        "avoid_night_arrival": avoid_night_arrival,
        "prefer_high_speed": prefer_high_speed,
        "price_sensitive": price_sensitive,
        "preferred_seat_type": preferred_seat_type,
        "max_extra_minutes": max_extra_minutes,
    }
    result = analyze(trains, departure_date=departure_date, preferences=preferences)
    result["from_station"] = search.get("from_station")
    result["to_station"] = search.get("to_station")
    result["departure_date"] = departure_date
    result["total_trains"] = search.get("total")
    return result
