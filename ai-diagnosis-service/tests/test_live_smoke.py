"""真实 DeepSeek smoke（默认跳过）。

仅当本机存在 DEEPSEEK_API_KEY 时执行；不得把 Key 写入代码、日志或仓库。
运行： pytest -m live

验收语义：live smoke 只验证**服务侧安全边界**，不把模型的证据质量当成服务失败。

- `dropped` = 模型提交、但被服务端 Evidence Validator 拒绝的条目（path 语法非法 / path 不存在 /
  duplicate path / `counter_presence` 不严格为 true）。真实非确定性模型给出这类引用是**预期现象**，
  Validator 的职责正是拦截它们；因此这里**不要求 `dropped == 0`**，它属于模型质量 telemetry。
- 被 dropped 的 evidence 绝不会出现在最终 `result.evidence` 中，该性质由 offline validator 单测
  （tests/test_evidence_validator.py）保证，不属于 live smoke 的验收范围。
"""

import os

import pytest

from config import Settings
from models.context import IncidentContext
from services.diagnosis_service import DiagnosisService

pytestmark = pytest.mark.live

_HAS_KEY = bool(os.environ.get("DEEPSEEK_API_KEY", "").strip())


def _assert_service_boundaries(result, settings) -> None:
    """服务侧安全边界：统计自洽、evidence 与 accepted 一致、上限被执行、无服务层错误码。"""
    v = result.evidence_validation

    # 全局不变量：每条 submitted 证据恰好落入 accepted / dropped / over_limit 之一
    assert v.submitted == v.accepted + v.dropped + v.over_limit
    # 最终 evidence 与 accepted 严格一致（dropped / over_limit 都不得出现在结果里）
    assert len(result.evidence) == v.accepted
    # 服务端输出上限必须被真正执行
    assert v.accepted <= settings.max_evidence_items
    # 服务层失败（UNAVAILABLE / INTERNAL）必然带 error_code，这里要求没有任何服务层错误
    assert result.error_code is None


@pytest.mark.skipif(not _HAS_KEY, reason="本机未配置 DEEPSEEK_API_KEY，跳过真实调用")
def test_live_diagnosis_on_real_fixture(context_payload):
    settings = Settings()
    service = DiagnosisService(settings)

    result = service.diagnose(IncidentContext.model_validate(context_payload))

    # 真实调用只允许两种正常语义结果；服务层失败（UNAVAILABLE）视为 smoke 失败
    assert result.diagnosis_status in {"DIAGNOSED", "INSUFFICIENT_EVIDENCE"}, result.error_code
    _assert_service_boundaries(result, settings)

    if result.diagnosis_status == "DIAGNOSED":
        assert result.root_cause
        assert result.evidence_validation.accepted > 0, "DIAGNOSED 必须至少有 1 条已回校验证据"
        assert result.evidence


@pytest.mark.skipif(not _HAS_KEY, reason="本机未配置 DEEPSEEK_API_KEY，跳过真实调用")
def test_live_diagnosis_with_missing_redis_fixture(missing_redis_payload):
    """主证据缺失时，期望模型选择 INSUFFICIENT_EVIDENCE 而不是编造根因。"""
    settings = Settings()
    service = DiagnosisService(settings)

    result = service.diagnose(IncidentContext.model_validate(missing_redis_payload))

    assert result.diagnosis_status in {"DIAGNOSED", "INSUFFICIENT_EVIDENCE"}, result.error_code
    _assert_service_boundaries(result, settings)

    if result.diagnosis_status == "DIAGNOSED":
        assert result.evidence_validation.accepted > 0, "DIAGNOSED 必须至少有 1 条已回校验证据"
        assert result.evidence
