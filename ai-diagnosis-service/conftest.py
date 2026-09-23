"""pytest 共享装置：fixture 加载与 stub 模型客户端。"""

import json
import pathlib
from typing import Optional

import pytest

from models.context import IncidentContext

FIXTURE_DIR = pathlib.Path(__file__).parent / "tests" / "fixtures"


def load_fixture(name: str) -> dict:
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))


@pytest.fixture
def context_payload() -> dict:
    """V2-2.1 冻结契约的真实（已脱敏）样本。"""
    return load_fixture("incident_context_v2_2_1.json")


@pytest.fixture
def missing_redis_payload() -> dict:
    """券维度 Redis 采集中断的派生样本。"""
    return load_fixture("incident_context_missing_redis.json")


@pytest.fixture
def context(context_payload: dict) -> IncidentContext:
    return IncidentContext.model_validate(context_payload)


class StubDeepSeekClient:
    """可编程的模型客户端桩：返回给定 payload / 原始文本，或抛给定错误。"""

    def __init__(
        self,
        payload: Optional[dict] = None,
        error: Optional[Exception] = None,
        raw_text: Optional[str] = None,
    ) -> None:
        self._payload = payload
        self._error = error
        self._raw_text = raw_text
        self.calls = 0
        self.last_prompt: Optional[str] = None

    def complete_json(self, user_prompt: str) -> str:
        self.calls += 1
        self.last_prompt = user_prompt
        if self._error is not None:
            raise self._error
        if self._raw_text is not None:
            return self._raw_text
        return json.dumps(self._payload, ensure_ascii=False)
