"""配置加载：与 ai-service 复用同一套环境变量约定（AI_LLM_*）。"""
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

_MODULE_ROOT = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    """Agent 运行配置，全部可用环境变量 / .env 覆盖。"""

    model_config = SettingsConfigDict(
        env_file=(str(_MODULE_ROOT / ".env"), ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ===== Agent 服务 =====
    agent_port: int = 10007
    # 12306 网关地址（gateway-service，路由前缀 /api/{svc}-service/** 原样转发）
    base_12306_url: str = "http://127.0.0.1:10000"
    http_timeout: float = 15.0

    # ===== LLM（DashScope OpenAI 兼容模式，与 ai-service 同约定）=====
    ai_llm_api_key: str = ""
    ai_llm_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    ai_llm_model: str = "qwen3-max"
    ai_llm_temperature: float = 0.2
    ai_llm_timeout_seconds: int = 30

    # ===== 演示账号（仅 CLI / 冒烟用，留空则必须显式登录）=====
    demo_username: str = ""
    demo_password: str = ""


settings = Settings()
