"""waitlist_scorer 单测：确定性启发式打分，不依赖网络/LLM/时间（today 注入）。"""
from datetime import date

from app.planning.waitlist_scorer import (
    BAND_HIGH,
    BAND_LOW,
    DISCLAIMER,
    analyze,
    estimate_waitlist_success,
)

TODAY = date(2026, 9, 12)


def _train(train_id, number, dep_time, arr_time, seats):
    return {
        "train_id": train_id,
        "train_number": number,
        "departure": "北京南",
        "arrival": "杭州东",
        "departure_time": dep_time,
        "arrival_time": arr_time,
        "duration": "05:18",
        "sale_status": 0,
        "seats": seats,
    }


def _seat(st, name, qty, price, cand):
    return {
        "seat_type": st,
        "seat_name": name,
        "quantity": qty,
        "price_yuan": price,
        "candidate_allowed": cand,
    }


def test_success_rate_peak_window_high():
    # 距发车 4 天（退票释放高峰）+ 夜间冷门 + 普通车 + 高等级席别 → 偏高
    est = estimate_waitlist_success(
        seat_type=1, train_number="K101", departure_time="23:30",
        departure_date="2026-09-16", today=TODAY,
    )
    assert est["band"] == BAND_HIGH
    assert est["success_pct"] >= 70
    assert any("退票/改签释放高峰" in f for f in est["factors"])


def test_success_rate_same_day_peak_low():
    # 当天 + 黄金时段 + 高铁 + 二等座 → 偏低
    est = estimate_waitlist_success(
        seat_type=2, train_number="G35", departure_time="08:30",
        departure_date="2026-09-12", today=TODAY,
    )
    assert est["band"] == BAND_LOW
    assert est["success_pct"] < 45


def test_success_rate_clamped():
    est = estimate_waitlist_success(
        seat_type=2, train_number="G1", departure_time="08:00",
        departure_date="2026-09-12", today=TODAY,
    )
    assert 5 <= est["success_pct"] <= 95


def test_analyze_buckets_and_recommendation():
    trains = [
        # G35 全售罄、可候补
        _train("1", "G35", "09:56", "15:14", [
            _seat(0, "商务座", 0, 1748.0, True),
            _seat(2, "二等座", 0, 623.0, True),
        ]),
        # G39 二等座有票
        _train("2", "G39", "19:04", "23:22", [
            _seat(2, "二等座", 12, 623.0, False),
        ]),
    ]
    result = analyze(trains, departure_date="2026-09-16", today=TODAY)

    assert len(result["waitlist"]) == 2
    assert len(result["buy_now"]) == 1
    assert result["buy_now"][0]["train_number"] == "G39"
    # 有票优先：顶层建议应推 buy_now
    assert result["recommendation"]["primary_type"] == "buy_now"
    assert "G39" in result["recommendation"]["headline"]
    assert result["disclaimer"] == DISCLAIMER


def test_analyze_all_soldout_recommends_waitlist():
    trains = [
        _train("1", "G35", "09:56", "15:14", [
            _seat(2, "二等座", 0, 623.0, True),
        ]),
    ]
    result = analyze(trains, departure_date="2026-09-16", today=TODAY)
    assert result["buy_now"] == []
    assert result["recommendation"]["primary_type"] == "waitlist"
    assert "候补" in result["recommendation"]["headline"]
    assert "%" in result["recommendation"]["headline"]


def test_analyze_soldout_not_waitlistable():
    trains = [
        _train("1", "G35", "09:56", "15:14", [
            _seat(2, "二等座", 0, 623.0, False),  # 售罄且不可候补
        ]),
    ]
    result = analyze(trains, departure_date="2026-09-16", today=TODAY)
    assert len(result["unavailable"]) == 1
    assert result["recommendation"]["primary_type"] == "none"


def test_analyze_preference_avoid_night():
    trains = [
        # 夜间到达
        _train("2", "G39", "19:04", "23:22", [_seat(2, "二等座", 12, 623.0, False)]),
        # 白天到达
        _train("5", "G57", "08:00", "13:30", [_seat(2, "二等座", 20, 600.0, False)]),
    ]
    result = analyze(
        trains, departure_date="2026-09-16",
        preferences={"avoid_night_arrival": True}, today=TODAY,
    )
    # 白天到达的 G57 应排在夜间到达的 G39 前面
    assert result["buy_now"][0]["train_number"] == "G57"
    assert result["recommendation"]["primary"]["train_number"] == "G57"


def test_analyze_low_stock_bucket():
    trains = [
        _train("3", "G71", "10:00", "16:00", [_seat(2, "二等座", 3, 600.0, False)]),
    ]
    result = analyze(trains, departure_date="2026-09-16", today=TODAY)
    assert len(result["low_stock"]) == 1
    assert result["low_stock"][0]["quantity"] == 3
    assert result["recommendation"]["primary_type"] == "low_stock"
