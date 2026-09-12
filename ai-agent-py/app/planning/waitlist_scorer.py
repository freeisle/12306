"""场景三：候补购票智能推荐 —— 确定性启发式打分引擎（纯函数，绝不调用 LLM）。

设计边界（本场景最重要的架构判断）：
    「候补成功率」是一个可用规则刻画的确定性估算问题，交给算法；
    LLM 只负责①把用户模糊偏好翻译成 preferences 权重、②把算分结果讲成人话。
    本模块不 import 任何 LLM/网络依赖，输入车次余票 + 偏好，输出结构化推荐，可单测。

诚实声明：项目当前没有历史余票快照数据，无法训练真模型。这里给出的是**透明、可解释的
启发式估算**（成功率百分比 + 影响因子），并显式标注为「非官方估算，仅供决策参考」。
待接入余票快照采集流水线后，可将 estimate_waitlist_success 平滑替换为时序/梯度提升模型，
调用方（工具层与编排层）无需改动。

数据来源：ticket-service GET /ticket/query 的精简结果（见 app/tools/ticket_tools.py）：
    train: {train_id, train_number, departure, arrival, departure_time(HH:mm),
            arrival_time(HH:mm), duration, sale_status,
            seats: [{seat_type, seat_name, quantity, price_yuan, candidate_allowed}]}
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from typing import Any, Dict, List, Optional

# 余票「紧张」阈值：低于此值视为随时可能售罄，提示尽快下单或候补兜底。
LOW_QUANTITY_THRESHOLD = 5

# 成功率分档
BAND_HIGH = "高"
BAND_MID = "中"
BAND_LOW = "低"

DISCLAIMER = "候补成功率为启发式估算（非官方数据），仅供决策参考；实际以 12306 兑现为准。"

# 席别归类（VehicleSeatTypeEnum）
_BUSINESS = {0, 12}          # 商务座 / 特等座
_FIRST = {1}                 # 一等座
_SECOND = {2, 3}             # 二等座 / 二等包座
_SLEEPER = {4, 5, 6, 7, 9, 10}  # 各类卧铺
_HARD = {8, 11, 13}          # 硬座 / 软座 / 无座


@dataclass
class Preferences:
    """用户模糊偏好 → 打分权重。由 LLM 从自然语言理解后填充。"""

    avoid_night_arrival: bool = False   # 不想半夜到达（到达在 22:00-06:00 视为夜间）
    prefer_high_speed: bool = False     # 优先高铁/动车
    price_sensitive: bool = False       # 价格敏感，优先低价
    preferred_seat_type: Optional[int] = None  # 偏好席别码（如 2=二等座）
    max_extra_minutes: Optional[int] = None    # 相对最早可达，最多可接受晚到多少分钟

    @classmethod
    def from_dict(cls, raw: Optional[Dict[str, Any]]) -> "Preferences":
        raw = raw or {}
        seat = raw.get("preferred_seat_type")
        extra = raw.get("max_extra_minutes")
        return cls(
            avoid_night_arrival=bool(raw.get("avoid_night_arrival", False)),
            prefer_high_speed=bool(raw.get("prefer_high_speed", False)),
            price_sensitive=bool(raw.get("price_sensitive", False)),
            preferred_seat_type=int(seat) if seat is not None else None,
            max_extra_minutes=int(extra) if extra is not None else None,
        )


def _infer_train_kind(train_number: str) -> str:
    """由车次首字母推断车型：高铁/动车/城际 视为高速，其余普通。"""
    if not train_number:
        return "普通"
    head = train_number[0].upper()
    if head in ("G", "C"):
        return "高铁"
    if head == "D":
        return "动车"
    return "普通"


def _parse_hhmm(value: Optional[str]) -> Optional[int]:
    """把 'HH:mm' 解析成分钟数；失败返回 None。"""
    if not value:
        return None
    try:
        t = datetime.strptime(str(value).strip(), "%H:%M")
        return t.hour * 60 + t.minute
    except ValueError:
        return None


def _is_night(minute_of_day: Optional[int]) -> bool:
    if minute_of_day is None:
        return False
    hour = minute_of_day // 60
    return hour >= 22 or hour < 6


def _is_peak_departure(minute_of_day: Optional[int]) -> bool:
    """黄金出行时段：07:00-10:00 与 17:00-20:00，需求旺、候补更难兑现。"""
    if minute_of_day is None:
        return False
    hour = minute_of_day // 60
    return (7 <= hour < 10) or (17 <= hour < 20)


def _days_to_departure(departure_date: Optional[str], today: date) -> Optional[int]:
    if not departure_date:
        return None
    try:
        d = datetime.strptime(str(departure_date).strip(), "%Y-%m-%d").date()
    except ValueError:
        return None
    return (d - today).days


def estimate_waitlist_success(
    seat_type: int,
    train_number: str,
    departure_time: Optional[str],
    departure_date: Optional[str],
    today: Optional[date] = None,
) -> Dict[str, Any]:
    """估算某车次某席别的候补兑现成功率（0-100 的百分比 + 分档 + 影响因子）。

    纯确定性规则，因子透明可追溯：
      · 距发车天数：候补兑现来自他人退票/改签，3-5 天窗口释放最多、成功率最高；
        当天太仓促、超远期需求不确定，均偏低。
      · 发车时段：黄金时段需求旺 → 更难；夜间冷门 → 更易。
      · 车型：高铁/动车更抢手 → 更难；普通车 → 更易。
      · 席别：二等座需求最大 → 更难；商务/一等座需求小 → 更易；长途卧铺刚需 → 略难。
    """
    today = today or date.today()
    factors: List[str] = []
    pct = 50.0  # 基准

    # 因子 1：距发车天数
    days = _days_to_departure(departure_date, today)
    if days is None:
        factors.append("出发日期未知（按中性处理）")
    elif days < 0:
        pct -= 30
        factors.append("出发日期已过（不建议候补）")
    elif days == 0:
        pct -= 15
        factors.append("当天发车（兑现窗口太短）")
    elif days <= 2:
        pct += 5
        factors.append(f"距发车{days}天（临近有一定退票释放）")
    elif days <= 5:
        pct += 20
        factors.append(f"距发车{days}天（退票/改签释放高峰期）")
    elif days <= 10:
        pct += 8
        factors.append(f"距发车{days}天（释放较可观）")
    else:
        pct -= 2
        factors.append(f"距发车{days}天（超远期，需求不确定）")

    # 因子 2：发车时段
    dep_min = _parse_hhmm(departure_time)
    if _is_peak_departure(dep_min):
        pct -= 12
        factors.append("黄金时段发车（需求旺，候补更难）")
    elif _is_night(dep_min):
        pct += 8
        factors.append("夜间发车（冷门，候补更易）")

    # 因子 3：车型
    kind = _infer_train_kind(train_number)
    if kind in ("高铁", "动车"):
        pct -= 6
        factors.append(f"{kind}车次（更抢手）")
    else:
        pct += 4
        factors.append("普通车次（竞争相对小）")

    # 因子 4：席别
    if seat_type in _SECOND:
        pct -= 5
        factors.append("二等座（需求量最大）")
    elif seat_type in _BUSINESS or seat_type in _FIRST:
        pct += 5
        factors.append("高等级席别（需求相对小）")
    elif seat_type in _SLEEPER:
        pct -= 3
        factors.append("卧铺（长途刚需）")

    pct = max(5.0, min(95.0, pct))
    pct_int = int(round(pct))
    band = BAND_HIGH if pct_int >= 70 else (BAND_MID if pct_int >= 45 else BAND_LOW)
    return {"success_pct": pct_int, "band": band, "factors": factors}


def _preference_score(
    train: Dict[str, Any],
    seat: Dict[str, Any],
    prefs: Preferences,
    arrival_min: Optional[int],
    earliest_arrival: Optional[int],
) -> Dict[str, Any]:
    """为「有票可下单」方案打偏好匹配分（越高越靠前），并给出可读理由。"""
    score = 0.0
    notes: List[str] = []
    kind = _infer_train_kind(train.get("train_number", ""))

    if prefs.prefer_high_speed:
        if kind in ("高铁", "动车"):
            score += 20
            notes.append(f"{kind}，符合优先高速偏好")
        else:
            score -= 15
            notes.append("非高速车次")

    if prefs.avoid_night_arrival and _is_night(arrival_min):
        score -= 25
        notes.append("夜间到达（不符合偏好）")

    if prefs.price_sensitive:
        # 价格越低分越高，用相对量纲粗略归一
        price = float(seat.get("price_yuan") or 0.0)
        score += max(0.0, 20.0 - price / 10.0)
        notes.append(f"票价{price:.1f}元（价格敏感）")

    if prefs.preferred_seat_type is not None:
        if int(seat.get("seat_type", -1)) == prefs.preferred_seat_type:
            score += 15
            notes.append("命中偏好席别")

    extra_min = None
    if prefs.max_extra_minutes is not None and arrival_min is not None and earliest_arrival is not None:
        extra_min = arrival_min - earliest_arrival
        if extra_min > prefs.max_extra_minutes:
            score -= 30
            notes.append(f"比最早到达晚{extra_min}分钟，超出可接受范围")
        elif extra_min > 0:
            notes.append(f"比最早到达晚{extra_min}分钟")

    return {"preference_score": round(score, 2), "notes": notes, "extra_minutes": extra_min}


def analyze(
    trains: List[Dict[str, Any]],
    departure_date: Optional[str],
    preferences: Optional[Dict[str, Any]] = None,
    today: Optional[date] = None,
) -> Dict[str, Any]:
    """对一批候选车次做候补/改乘分析，产出结构化推荐。

    Args:
        trains: search_trains 的精简车次列表。
        departure_date: 出发日期 yyyy-MM-dd（用于距发车天数因子）。
        preferences: 用户偏好字典（见 Preferences）。
        today: 基准「今日」，默认 date.today()，测试可注入。

    Returns:
        {
          route_date, preferences_applied,
          buy_now: [...],      # 有票可直接下单（按偏好分排序）
          low_stock: [...],    # 余票紧张（1..阈值-1），提示尽快下单
          waitlist: [...],     # 售罄可候补，含 success_pct/band/factors
          unavailable: [...],  # 售罄且不可候补
          recommendation: {headline, primary_type, primary, reason},
          disclaimer
        }
    """
    today = today or date.today()
    prefs = Preferences.from_dict(preferences)

    buy_now: List[Dict[str, Any]] = []
    low_stock: List[Dict[str, Any]] = []
    waitlist: List[Dict[str, Any]] = []
    unavailable: List[Dict[str, Any]] = []

    # 先求最早到达时间，供 max_extra_minutes 偏好使用
    arrival_minutes: List[int] = []
    for tr in trains:
        am = _parse_hhmm(tr.get("arrival_time"))
        if am is not None:
            arrival_minutes.append(am)
    earliest_arrival = min(arrival_minutes) if arrival_minutes else None

    for tr in trains:
        base = {
            "train_id": tr.get("train_id"),
            "train_number": tr.get("train_number"),
            "departure": tr.get("departure"),
            "arrival": tr.get("arrival"),
            "departure_time": tr.get("departure_time"),
            "arrival_time": tr.get("arrival_time"),
            "duration": tr.get("duration"),
            "train_kind": _infer_train_kind(tr.get("train_number", "")),
        }
        arr_min = _parse_hhmm(tr.get("arrival_time"))
        for seat in tr.get("seats") or []:
            seat_type = int(seat.get("seat_type", -1))
            quantity = int(seat.get("quantity") or 0)
            candidate_allowed = bool(seat.get("candidate_allowed"))
            item = dict(base)
            item.update(
                {
                    "seat_type": seat_type,
                    "seat_name": seat.get("seat_name"),
                    "quantity": quantity,
                    "price_yuan": float(seat.get("price_yuan") or 0.0),
                }
            )
            if quantity >= LOW_QUANTITY_THRESHOLD:
                item.update(_preference_score(tr, seat, prefs, arr_min, earliest_arrival))
                buy_now.append(item)
            elif quantity > 0:
                item.update(_preference_score(tr, seat, prefs, arr_min, earliest_arrival))
                item["warning"] = f"仅剩{quantity}张，随时可能售罄"
                low_stock.append(item)
            elif candidate_allowed:
                est = estimate_waitlist_success(
                    seat_type,
                    tr.get("train_number", ""),
                    tr.get("departure_time"),
                    departure_date,
                    today=today,
                )
                item.update(est)
                waitlist.append(item)
            else:
                item["reason"] = "售罄且该席别不支持候补"
                unavailable.append(item)

    # 排序：有票方案按偏好分降序、同分按到达时间升序；候补按成功率降序
    buy_now.sort(key=lambda x: (-x.get("preference_score", 0.0), _parse_hhmm(x.get("arrival_time")) or 9999))
    low_stock.sort(key=lambda x: (-x.get("preference_score", 0.0), x.get("quantity", 0)))
    waitlist.sort(key=lambda x: -x.get("success_pct", 0))

    recommendation = _build_recommendation(buy_now, low_stock, waitlist, unavailable, prefs)

    return {
        "route_date": departure_date,
        "preferences_applied": {
            "avoid_night_arrival": prefs.avoid_night_arrival,
            "prefer_high_speed": prefs.prefer_high_speed,
            "price_sensitive": prefs.price_sensitive,
            "preferred_seat_type": prefs.preferred_seat_type,
            "max_extra_minutes": prefs.max_extra_minutes,
        },
        "buy_now": buy_now,
        "low_stock": low_stock,
        "waitlist": waitlist,
        "unavailable": unavailable,
        "recommendation": recommendation,
        "disclaimer": DISCLAIMER,
    }


def _build_recommendation(
    buy_now: List[Dict[str, Any]],
    low_stock: List[Dict[str, Any]],
    waitlist: List[Dict[str, Any]],
    unavailable: List[Dict[str, Any]],
    prefs: Preferences,
) -> Dict[str, Any]:
    """综合各桶给出顶层建议：优先推有票方案，其次紧张票，最后候补兜底。"""
    if buy_now:
        primary = buy_now[0]
        headline = (
            f"推荐直接购买 {primary['train_number']} {primary['seat_name']}"
            f"（余票{primary['quantity']}张，{primary['price_yuan']:.1f}元）"
        )
        reason = "、".join(primary.get("notes") or []) or "余票充足且最符合您的偏好"
        return {"headline": headline, "primary_type": "buy_now", "primary": primary, "reason": reason}

    if low_stock:
        primary = low_stock[0]
        headline = (
            f"{primary['train_number']} {primary['seat_name']}仅剩{primary['quantity']}张，建议尽快下单"
        )
        reason = "余票紧张，若犹豫可同时候补兜底"
        return {"headline": headline, "primary_type": "low_stock", "primary": primary, "reason": reason}

    if waitlist:
        primary = waitlist[0]
        headline = (
            f"当前无票，建议候补 {primary['train_number']} {primary['seat_name']}"
            f"（估算成功率约{primary['success_pct']}%，{primary['band']}）"
        )
        reason = "、".join(primary.get("factors") or [])
        return {"headline": headline, "primary_type": "waitlist", "primary": primary, "reason": reason}

    return {
        "headline": "该线路当前无可售或可候补的席别",
        "primary_type": "none",
        "primary": None,
        "reason": "建议更换日期、席别或出发/到达站后重试",
    }
