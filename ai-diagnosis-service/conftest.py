"""pytest 共享装置：fixture 加载、临时 KB 目录与 stub 模型客户端。"""

import json
import pathlib
import shutil
import uuid
from typing import Optional

import pytest

from models.context import IncidentContext

FIXTURE_DIR = pathlib.Path(__file__).parent / "tests" / "fixtures"
# 临时目录放在服务目录内（.pytest-tmp/，已 gitignore）：
# 沙箱环境不允许 pytest 默认的 %TEMP%\pytest-of-* 写入，且这样不污染系统临时区。
TMP_ROOT = pathlib.Path(__file__).parent / ".pytest-tmp"


def load_fixture(name: str) -> dict:
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))


def assert_submitted_invariant(validation) -> None:
    """V2-3.3 冻结不变量：每条 submitted 证据必须恰好落入三种去向之一。

    submitted == accepted（进入最终 evidence）+ dropped（校验拒绝）+ over_limit（合法但被输出上限截断）
    """
    assert validation.submitted == (
        validation.accepted + validation.dropped + validation.over_limit
    ), (
        f"submitted={validation.submitted} != accepted={validation.accepted} "
        f"+ dropped={validation.dropped} + over_limit={validation.over_limit}"
    )


@pytest.fixture
def context_payload() -> dict:
    """V2-2.1 冻结契约的真实（已脱敏）样本。"""
    return load_fixture("incident_context_v2_2_1.json")


@pytest.fixture
def missing_redis_payload() -> dict:
    """券维度 Redis 采集中断的派生样本。"""
    return load_fixture("incident_context_missing_redis.json")


@pytest.fixture
def runbook_tmp_dir():
    """每个用例独立的临时目录（用于构造合法/损坏的 Runbook KB）。"""
    TMP_ROOT.mkdir(parents=True, exist_ok=True)
    directory = TMP_ROOT / f"case-{uuid.uuid4().hex[:8]}"
    directory.mkdir()
    try:
        yield directory
    finally:
        shutil.rmtree(directory, ignore_errors=True)


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
