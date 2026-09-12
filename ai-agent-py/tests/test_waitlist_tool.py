"""recommend_ticket_options 工具接线冒烟测试（模拟真实 DB 状态，不依赖后端/网络）。

场景对齐测试数据 12306-springcloud-ticket-waitlist-demo.sql：
    G35(train_id=1) 北京南→杭州东 全售罄 → 各席别 quantity=0 且 candidate=true（Java 修复后）
    G39(train_id=2) 北京南→杭州东 二等座有票
"""
from app.tools import recommend_ticket_options
from app.tools.client import reset_current_session, set_current_session


class _FakeSession:
    """最小会话桩：只实现 search_trains 用到的两个方法。"""

    def resolve_station_code(self, city_or_station):
        mapping = {"北京": ("北京南", "VNP"), "北京南": ("北京南", "VNP"),
                   "杭州": ("杭州东", "HGH"), "杭州东": ("杭州东", "HGH")}
        return mapping.get(city_or_station, (city_or_station, city_or_station))

    def get(self, path, params=None, auth_required=True):
        # 模拟 ticket-service /ticket/query 的精简前原始响应
        return {
            "trainList": [
                {
                    "trainId": "1", "trainNumber": "G35",
                    "departure": "北京南", "arrival": "杭州东",
                    "departureTime": "09:56", "arrivalTime": "15:14",
                    "duration": "05:18", "saleStatus": 0,
                    "seatClassList": [
                        {"type": 0, "quantity": 0, "price": 1748.0, "candidate": True},
                        {"type": 2, "quantity": 0, "price": 623.0, "candidate": True},
                    ],
                },
                {
                    "trainId": "2", "trainNumber": "G39",
                    "departure": "北京南", "arrival": "杭州东",
                    "departureTime": "19:04", "arrivalTime": "23:22",
                    "duration": "04:18", "saleStatus": 0,
                    "seatClassList": [
                        {"type": 2, "quantity": 12, "price": 623.0, "candidate": False},
                    ],
                },
            ]
        }


def test_tool_end_to_end_waitlist_vs_alternative():
    token = set_current_session(_FakeSession())
    try:
        result = recommend_ticket_options("北京", "杭州", "2026-09-16")
    finally:
        reset_current_session(token)

    # G35 两个席别售罄可候补
    assert len(result["waitlist"]) == 2
    assert all(w["train_number"] == "G35" for w in result["waitlist"])
    for w in result["waitlist"]:
        assert 5 <= w["success_pct"] <= 95
        assert w["band"] in ("高", "中", "低")
        assert w["factors"]

    # G39 有票 → buy_now，且顶层建议优先推有票改乘
    assert len(result["buy_now"]) == 1
    assert result["buy_now"][0]["train_number"] == "G39"
    assert result["recommendation"]["primary_type"] == "buy_now"
    assert "G39" in result["recommendation"]["headline"]

    # 元信息透传
    assert result["from_station"] == "北京南"
    assert result["to_station"] == "杭州东"
    assert result["total_trains"] == 2
    assert "估算" in result["disclaimer"]


def test_tool_all_soldout_gives_waitlist_headline():
    class _AllSold(_FakeSession):
        def get(self, path, params=None, auth_required=True):
            data = super().get(path, params, auth_required)
            data["trainList"] = [t for t in data["trainList"] if t["trainNumber"] == "G35"]
            return data

    token = set_current_session(_AllSold())
    try:
        result = recommend_ticket_options("北京", "杭州", "2026-09-16")
    finally:
        reset_current_session(token)

    assert result["buy_now"] == []
    assert result["recommendation"]["primary_type"] == "waitlist"
    assert "候补" in result["recommendation"]["headline"]
    assert "%" in result["recommendation"]["headline"]
