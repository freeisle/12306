"""12306 HTTP 客户端与会话注册表。

契约要点（核对自 Java 侧源码）：
- 统一响应信封 Result<T>：{"code":"0","message":...,"data":...,"success":true}，code=="0" 为成功；
- 登录 POST /api/user-service/v1/login，token 在 data.accessToken；
- 身份通过请求头 `Authorization: <accessToken>` 携带（裸 JWT，无 Bearer 前缀），
  网关 TokenValidate 过滤器解析后向下游注入 userId/username/realName 头；
- 网关路由无 StripPrefix，服务级路径 == 网关级路径，端口 9000。
"""
from __future__ import annotations

import contextvars
import threading
from typing import Any, Dict, Optional

import httpx

from app.config import settings


class ToolCallError(Exception):
    """工具调用失败（网络 / 业务 code!=0 / 未登录），消息可直接回给 LLM 自救。"""


class Session:
    """单个对话线程(thread_id)对应的 12306 会话（持 token 的 httpx 客户端）。"""

    def __init__(self, base_url: str | None = None, timeout: float | None = None):
        self.base_url = (base_url or settings.base_12306_url).rstrip("/")
        self.timeout = timeout or settings.http_timeout
        self.thread_id: Optional[str] = None
        self.token: Optional[str] = None
        self.username: Optional[str] = None
        self.user_id: Optional[str] = None
        self.real_name: Optional[str] = None
        self._http = httpx.Client(timeout=self.timeout)
        # 站点表缓存：{name: code}，另附 spell/region 索引
        self._station_cache: Optional[Dict[str, dict]] = None

    # ---------- 登录 ----------
    def login(self, username: str, password: str) -> dict:
        data = self.post(
            "/api/user-service/v1/login",
            json={"usernameOrMailOrPhone": username, "password": password},
            auth_required=False,
        )
        self.token = data.get("accessToken")
        self.username = data.get("username")
        self.user_id = str(data.get("userId"))
        self.real_name = data.get("realName")
        if not self.token:
            raise ToolCallError("登录响应缺少 accessToken")
        return data

    def adopt_token(self, token: str, username: Optional[str] = None) -> dict:
        """复用既有 12306 登录态（如控制台 Cookie 中的 JWT），免密码登录。

        控制台登录后浏览器只持有 accessToken（JWT），不存明文密码；
        Agent 直接采纳该 token 作为 Authorization 头即可调用下游微服务。
        采纳后立即用一次轻量鉴权请求验证有效性，失败则回滚并抛 ToolCallError。
        """
        if not token or not token.strip():
            raise ToolCallError("access_token 为空，无法复用登录态")
        self.token = token.strip()
        if username:
            self.username = username
        try:
            self.get("/api/user-service/passenger/query")
        except ToolCallError as exc:
            self.token = None
            raise ToolCallError(f"复用的登录态已失效或无权限，请重新登录控制台：{exc}") from exc
        return {"username": self.username, "realName": self.real_name}

    @property
    def logged_in(self) -> bool:
        return bool(self.token)

    def require_login(self) -> None:
        if not self.token:
            raise ToolCallError(
                "当前会话未登录 12306。请先调用登录接口（/agent/session）或在本轮请求中携带账号密码。"
            )

    # ---------- HTTP ----------
    def _headers(self, auth_required: bool) -> dict:
        headers = {"Content-Type": "application/json"}
        if auth_required:
            self.require_login()
            headers["Authorization"] = self.token or ""
        return headers

    def _request(
        self,
        method: str,
        path: str,
        *,
        params: Optional[dict] = None,
        json: Optional[dict] = None,
        auth_required: bool = True,
    ) -> Any:
        url = f"{self.base_url}{path}"
        try:
            resp = self._http.request(
                method, url, params=params, json=json, headers=self._headers(auth_required)
            )
        except httpx.HTTPError as exc:
            raise ToolCallError(f"调用 12306 失败({path}): {exc}") from exc
        if resp.status_code == 401:
            raise ToolCallError(f"登录态失效或无权限({path})，请重新登录")
        if resp.status_code >= 400:
            raise ToolCallError(f"调用 12306 返回 HTTP {resp.status_code}({path})")
        try:
            body = resp.json()
        except ValueError as exc:
            raise ToolCallError(f"调用 12306 响应非 JSON({path})") from exc
        return self._unwrap(body, path)

    @staticmethod
    def _unwrap(body: Any, path: str) -> Any:
        if not isinstance(body, dict) or "code" not in body:
            raise ToolCallError(f"调用 12306 响应结构异常({path})")
        if str(body.get("code")) != "0":
            raise ToolCallError(body.get("message") or f"业务失败 code={body.get('code')}({path})")
        return body.get("data")

    def get(self, path: str, params: Optional[dict] = None, auth_required: bool = True) -> Any:
        return self._request("GET", path, params=params, auth_required=auth_required)

    def post(self, path: str, json: Optional[dict] = None, auth_required: bool = True) -> Any:
        return self._request("POST", path, json=json, auth_required=auth_required)

    # ---------- 站点表 ----------
    def stations(self) -> Dict[str, dict]:
        """全量站点 {name: {code, spell, regionName}}，进程内每会话缓存一次。"""
        if self._station_cache is None:
            data = self.get("/api/ticket-service/station/all", auth_required=False)
            cache: Dict[str, dict] = {}
            for item in data or []:
                cache[item["name"]] = {
                    "code": item.get("code"),
                    "spell": (item.get("spell") or "").lower(),
                    "region": item.get("regionName"),
                }
            self._station_cache = cache
        return self._station_cache

    def resolve_station_code(self, city_or_station: str) -> tuple[str, str]:
        """城市名/站名 → (站名, 站码)。支持全名、城市前缀（北京→北京南优先）、拼音。"""
        table = self.stations()
        key = city_or_station.strip()
        if not key:
            raise ToolCallError("出发地/目的地不能为空")
        if key in table:
            return key, table[key]["code"]
        # 城市前缀：优先主站/南/西/东顺序不敏感，取第一个匹配
        for name, meta in table.items():
            if name.startswith(key):
                return name, meta["code"]
        spell = key.lower()
        for name, meta in table.items():
            if meta["spell"] and (meta["spell"] == spell or meta["spell"].startswith(spell)):
                return name, meta["code"]
        raise ToolCallError(f"无法识别的车站或城市：{city_or_station}（请改用具体站名，如「北京南」）")


# ---------- 会话注册表：thread_id -> Session ----------
_SESSIONS: Dict[str, Session] = {}
_LOCK = threading.Lock()

# ---------- 当前会话注入（contextvar）----------
# 工具函数对 bind_tools 暴露的签名只能含 JSON 可序列化参数，
# 因此 Session 不进入签名，而由 tools 节点在执行前注入上下文。
_CURRENT_SESSION: contextvars.ContextVar[Optional[Session]] = contextvars.ContextVar(
    "current_12306_session", default=None
)


def set_current_session(session: Session) -> contextvars.Token:
    return _CURRENT_SESSION.set(session)


def reset_current_session(token: contextvars.Token) -> None:
    _CURRENT_SESSION.reset(token)


def current_session() -> Session:
    session = _CURRENT_SESSION.get()
    if session is None:
        raise ToolCallError("内部错误：会话未注入")
    return session


def get_session(thread_id: str) -> Session:
    with _LOCK:
        session = _SESSIONS.get(thread_id)
        if session is None:
            session = Session()
            session.thread_id = thread_id
            _SESSIONS[thread_id] = session
        return session


def put_session(thread_id: str, session: Session) -> None:
    with _LOCK:
        session.thread_id = thread_id
        _SESSIONS[thread_id] = session


def drop_session(thread_id: str) -> None:
    with _LOCK:
        _SESSIONS.pop(thread_id, None)
