"""FastAPI 入口（端口 10007）：/agent/session、/agent/chat、/agent/resume、/agent/health。

HITL 契约：chat/resume 的响应中 pending 非空表示图被 interrupt 挂起，
客户端渲染 pending.payload（confirm_order / confirm_pay）后调用 /agent/resume 恢复。
"""
from __future__ import annotations

import uuid
from typing import Any, Dict, Optional

from fastapi import FastAPI, HTTPException
from langchain_core.messages import AIMessage, HumanMessage
from langgraph.types import Command
from pydantic import BaseModel, Field

from app.config import settings
from app.graph import build_graph
from app.tools.client import ToolCallError, get_session

app = FastAPI(title="12306 对话式购票 Agent", version="0.1.0")

GRAPH = build_graph()

if not settings.ai_llm_api_key:
    print(
        "[ai-agent] 警告：未检测到 AI_LLM_API_KEY！\n"
        "[ai-agent] 请在 ai-agent-py/.env 中配置（模板见 .env.example），\n"
        "[ai-agent] 注意：配置只在进程启动时读取一次，修改后必须重启本服务。"
    )


class SessionReq(BaseModel):
    username: Optional[str] = None
    password: Optional[str] = None
    access_token: Optional[str] = Field(
        default=None, description="复用既有 12306 登录态（控制台 Cookie 的 JWT），免密码"
    )


class ChatReq(BaseModel):
    message: str
    thread_id: Optional[str] = None
    username: Optional[str] = None
    password: Optional[str] = None
    access_token: Optional[str] = None


class ResumeReq(BaseModel):
    thread_id: str
    approved: bool
    note: str = Field(default="", description="拒绝/补充意见，会回传给模型")
    access_token: Optional[str] = None


def _config(thread_id: str) -> Dict[str, Any]:
    return {"configurable": {"thread_id": thread_id}}


def _ensure_login(thread_id: str, username: Optional[str], password: Optional[str],
                  access_token: Optional[str] = None) -> None:
    session = get_session(thread_id)
    if session.logged_in:
        return
    if access_token:
        try:
            session.adopt_token(access_token, username)
        except ToolCallError as exc:
            raise HTTPException(status_code=401, detail=str(exc)) from exc
    elif username and password:
        try:
            session.login(username, password)
        except ToolCallError as exc:
            raise HTTPException(status_code=401, detail=str(exc)) from exc


def _respond(thread_id: str) -> Dict[str, Any]:
    snapshot = GRAPH.get_state(_config(thread_id))
    messages = snapshot.values.get("messages") or []
    reply = ""
    for msg in reversed(messages):
        if isinstance(msg, AIMessage) and isinstance(msg.content, str) and msg.content.strip():
            reply = msg.content
            break
    pending = None
    if snapshot.next:
        for task in snapshot.tasks:
            for item in getattr(task, "interrupts", []) or []:
                pending = {"type": (item.value or {}).get("type"), "payload": item.value}
                break
            if pending:
                break
    return {"thread_id": thread_id, "reply": reply, "pending": pending, "done": not snapshot.next}


@app.get("/")
def root() -> Dict[str, Any]:
    """根路径导航：浏览器直接打开时给出接口清单与 Swagger 文档地址。"""
    return {
        "service": "12306 对话式购票 Agent（阶段二 · 场景一）",
        "docs": "/docs",
        "endpoints": {
            "POST /agent/session": "登录 12306 并创建会话，返回 thread_id",
            "POST /agent/chat": "发送对话消息，推进状态机",
            "POST /agent/resume": "恢复人工确认 interrupt（approved/note）",
            "GET /agent/health": "探活",
        },
    }


@app.get("/agent/health")
def health() -> Dict[str, Any]:
    key = settings.ai_llm_api_key or ""
    hint = f"{key[:7]}…{key[-4:]}" if len(key) > 12 else ""
    return {
        "status": "ok",
        "model": settings.ai_llm_model,
        "base_12306_url": settings.base_12306_url,
        "port": settings.agent_port,
        # 密钥可见性：配置只在进程启动时读取一次，改 .env 后必须重启；
        # 用 llm_key_hint 可核对"运行中的进程"加载的是哪把 key。
        "llm_key_set": bool(key),
        "llm_key_hint": hint,
        "llm_base_url": settings.ai_llm_base_url,
    }


@app.post("/agent/session")
def create_session(req: SessionReq) -> Dict[str, Any]:
    thread_id = uuid.uuid4().hex
    session = get_session(thread_id)
    if req.access_token:
        try:
            session.adopt_token(req.access_token, req.username)
        except ToolCallError as exc:
            raise HTTPException(status_code=401, detail=str(exc)) from exc
    elif req.username and req.password:
        try:
            session.login(req.username, req.password)
        except ToolCallError as exc:
            raise HTTPException(status_code=401, detail=str(exc)) from exc
    else:
        raise HTTPException(
            status_code=400,
            detail="请提供 access_token（复用控制台登录态）或 username+password",
        )
    return {"thread_id": thread_id, "username": session.username, "real_name": session.real_name}


@app.post("/agent/chat")
def chat(req: ChatReq) -> Dict[str, Any]:
    thread_id = req.thread_id or uuid.uuid4().hex
    _ensure_login(thread_id, req.username, req.password, req.access_token)
    snapshot = GRAPH.get_state(_config(thread_id))
    if snapshot.next:
        raise HTTPException(status_code=409, detail="存在待人工确认的 interrupt，请先调用 /agent/resume")
    GRAPH.invoke({"messages": [HumanMessage(content=req.message)]}, _config(thread_id))
    return _respond(thread_id)


@app.post("/agent/resume")
def resume(req: ResumeReq) -> Dict[str, Any]:
    snapshot = GRAPH.get_state(_config(req.thread_id))
    if not snapshot.next:
        raise HTTPException(status_code=409, detail="当前没有待确认的 interrupt")
    _ensure_login(req.thread_id, None, None, req.access_token)
    GRAPH.invoke(Command(resume={"approved": req.approved, "note": req.note}), _config(req.thread_id))
    return _respond(req.thread_id)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=settings.agent_port)
