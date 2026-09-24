"""V2-3 AI Diagnosis Service 配置（环境变量驱动，凭据不入库、不回显）。"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """服务配置。

    所有项均可通过环境变量覆盖；`.env` 仅用于本地开发且已被 .gitignore 排除。
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # ---- DeepSeek（与 Java 侧同名环境变量，便于统一注入）----
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-flash"
    deepseek_timeout_seconds: float = 40.0
    deepseek_max_tokens: int = 1600
    deepseek_temperature: float = 0.0

    # ---- 契约闸门 ----
    # 只接受列出的 context_version；契约升级必须显式升版，不做隐式前向兼容
    supported_context_versions: str = "v2-2.1"

    # ---- 轻量长度保护（严格请求体限流留到 V2-4）----
    max_context_chars: int = 200_000
    max_prompt_chars: int = 120_000

    # ---- 输出上限 ----
    max_evidence_items: int = 10
    max_actions: int = 5

    # ---- Runbook KB / RAG（V2-5）----
    # KB 目录缺失/不可读/为空/全部非法时一律退化 no-RAG（不 fail fast）
    rag_enabled: bool = True
    runbooks_dir: str = "runbooks"
    # 注入 prompt 的 runbook 段落总长度上限；超出时整条丢弃低排名条目（不截断正文）
    max_runbook_section_chars: int = 4000

    log_level: str = "INFO"
    host: str = "127.0.0.1"
    port: int = 8000

    @property
    def supported_versions(self) -> list[str]:
        return [v.strip() for v in self.supported_context_versions.split(",") if v.strip()]

    @property
    def model_configured(self) -> bool:
        """是否具备调用外部模型的最小配置（不暴露任何凭据内容）。"""
        return bool(self.deepseek_api_key.strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
