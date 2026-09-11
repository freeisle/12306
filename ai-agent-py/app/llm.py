"""LLM 工厂：ChatOpenAI 走 DashScope OpenAI 兼容模式（与 ai-service 同约定）。"""
from functools import lru_cache

from langchain_openai import ChatOpenAI

from app.config import settings


@lru_cache(maxsize=1)
def get_model() -> ChatOpenAI:
    """单例 ChatOpenAI。bind_tools 由 graph 的 agent 节点按需调用。"""
    return ChatOpenAI(
        api_key=settings.ai_llm_api_key or "EMPTY",
        base_url=settings.ai_llm_base_url,
        model=settings.ai_llm_model,
        temperature=settings.ai_llm_temperature,
        timeout=settings.ai_llm_timeout_seconds,
    )
