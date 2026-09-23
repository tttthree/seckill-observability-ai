"""DeepSeek 客户端调用参数测试（离线，不发起真实请求）。

锁定 V2-3.2 的运行兼容性约束：
- 显式关闭 Thinking Mode（extra_body.thinking.type = disabled）
- 不传 reasoning_effort
- max_retries = 0（无 retry）
- 保留 temperature / max_tokens / response_format
"""

from types import SimpleNamespace

import services.deepseek_client as deepseek_client_module
from config import Settings
from services.deepseek_client import DeepSeekClient


class _RecordingOpenAI:
    """记录 OpenAI(...) 构造参数与 chat.completions.create(...) 调用参数。"""

    last_init_kwargs: dict = {}
    last_create_kwargs: dict = {}

    def __init__(self, **kwargs) -> None:
        _RecordingOpenAI.last_init_kwargs = kwargs
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    @staticmethod
    def _create(**kwargs):
        _RecordingOpenAI.last_create_kwargs = kwargs
        return SimpleNamespace(
            choices=[
                SimpleNamespace(
                    message=SimpleNamespace(
                        content='{"diagnosis_status":"INSUFFICIENT_EVIDENCE","evidence":[],'
                        '"recommended_actions":[],"insufficient_reason":"x"}'
                    )
                )
            ]
        )


def _install_fake_sdk(monkeypatch) -> None:
    monkeypatch.setattr(deepseek_client_module, "OpenAI", _RecordingOpenAI)


def test_thinking_mode_is_disabled(monkeypatch):
    _install_fake_sdk(monkeypatch)
    client = DeepSeekClient(Settings(deepseek_api_key="dummy-key"))

    client.complete_json("prompt")

    kwargs = _RecordingOpenAI.last_create_kwargs
    assert kwargs["extra_body"] == {"thinking": {"type": "disabled"}}


def test_no_reasoning_effort_is_sent(monkeypatch):
    _install_fake_sdk(monkeypatch)
    client = DeepSeekClient(Settings(deepseek_api_key="dummy-key"))

    client.complete_json("prompt")

    assert "reasoning_effort" not in _RecordingOpenAI.last_create_kwargs


def test_create_kwargs_keep_frozen_generation_settings(monkeypatch):
    _install_fake_sdk(monkeypatch)
    settings = Settings(deepseek_api_key="dummy-key")
    client = DeepSeekClient(settings)

    client.complete_json("prompt")

    kwargs = _RecordingOpenAI.last_create_kwargs
    assert kwargs["model"] == settings.deepseek_model
    assert kwargs["temperature"] == 0.0
    assert kwargs["max_tokens"] == 1600
    assert kwargs["response_format"] == {"type": "json_object"}
    assert kwargs["messages"][0]["role"] == "system"


def test_max_retries_is_zero(monkeypatch):
    _install_fake_sdk(monkeypatch)

    DeepSeekClient(Settings(deepseek_api_key="dummy-key"))

    init_kwargs = _RecordingOpenAI.last_init_kwargs
    assert init_kwargs["max_retries"] == 0
    assert init_kwargs["timeout"] == Settings().deepseek_timeout_seconds
    assert init_kwargs["base_url"] == Settings().deepseek_base_url


def test_client_not_constructed_without_api_key(monkeypatch):
    _install_fake_sdk(monkeypatch)

    client = DeepSeekClient(Settings(deepseek_api_key=""))

    assert client.configured is False


def test_default_model_is_deepseek_flash():
    """仓库默认模型与运行环境保持一致。"""
    assert Settings().deepseek_model == "deepseek-flash"
