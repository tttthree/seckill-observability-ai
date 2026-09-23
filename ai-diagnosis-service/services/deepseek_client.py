"""DeepSeek 客户端封装：单次调用、无 retry/backoff（retry/限流/熔断留 V2-4）。

只负责"发一次请求 + 把异常归类为稳定的 error_code"，不做业务判断。
"""

import logging
from typing import Optional

from openai import (
    APIConnectionError,
    APIStatusError,
    APITimeoutError,
    AuthenticationError,
    OpenAI,
    RateLimitError,
)

from config import Settings
from models.diagnosis import ErrorCode
from services.prompt_builder import SYSTEM_PROMPT

logger = logging.getLogger(__name__)


class ModelCallError(Exception):
    """模型调用失败，携带稳定 error_code；不携带原始异常细节以供对外输出。"""

    def __init__(self, code: ErrorCode, detail: str = "") -> None:
        super().__init__(code.value)
        self.code = code
        self.detail = detail


class DeepSeekClient:
    """DeepSeek（OpenAI 兼容）单次 JSON 调用。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client: Optional[OpenAI] = None
        if settings.model_configured:
            self._client = OpenAI(
                api_key=settings.deepseek_api_key,
                base_url=settings.deepseek_base_url,
                timeout=settings.deepseek_timeout_seconds,
                max_retries=0,  # V2-3 明确不重试
            )

    @property
    def configured(self) -> bool:
        return self._client is not None

    def complete_json(self, user_prompt: str) -> str:
        """调用一次并返回模型原始文本；失败抛 ModelCallError。"""
        if self._client is None:
            raise ModelCallError(ErrorCode.MODEL_NOT_CONFIGURED)

        try:
            response = self._client.chat.completions.create(
                model=self._settings.deepseek_model,
                messages=[
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": user_prompt},
                ],
                temperature=self._settings.deepseek_temperature,
                max_tokens=self._settings.deepseek_max_tokens,
                response_format={"type": "json_object"},
            )
        except APITimeoutError as exc:
            raise ModelCallError(ErrorCode.MODEL_TIMEOUT, type(exc).__name__) from exc
        except APIConnectionError as exc:
            raise ModelCallError(ErrorCode.MODEL_UNREACHABLE, type(exc).__name__) from exc
        except RateLimitError as exc:
            raise ModelCallError(ErrorCode.MODEL_RATE_LIMITED, type(exc).__name__) from exc
        except AuthenticationError as exc:
            raise ModelCallError(ErrorCode.MODEL_AUTH_ERROR, type(exc).__name__) from exc
        except APIStatusError as exc:
            raise ModelCallError(
                ErrorCode.MODEL_HTTP_ERROR, f"status={exc.status_code}"
            ) from exc
        except Exception as exc:  # noqa: BLE001 - 兜底归类，不向上抛原始异常
            raise ModelCallError(ErrorCode.MODEL_HTTP_ERROR, type(exc).__name__) from exc

        if not response.choices:
            raise ModelCallError(ErrorCode.MODEL_OUTPUT_INVALID, "empty choices")
        content = response.choices[0].message.content or ""
        if not content.strip():
            raise ModelCallError(ErrorCode.MODEL_OUTPUT_INVALID, "empty content")
        return content
