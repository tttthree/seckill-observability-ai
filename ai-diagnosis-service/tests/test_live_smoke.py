"""真实 DeepSeek smoke（默认跳过）。

仅当本机存在 DEEPSEEK_API_KEY 时执行；不得把 Key 写入代码、日志或仓库。
运行： pytest -m live
"""

import os

import pytest

from config import Settings
from conftest import assert_submitted_invariant
from models.context import IncidentContext
from services.diagnosis_service import DiagnosisService

pytestmark = pytest.mark.live

_HAS_KEY = bool(os.environ.get("DEEPSEEK_API_KEY", "").strip())


@pytest.mark.skipif(not _HAS_KEY, reason="本机未配置 DEEPSEEK_API_KEY，跳过真实调用")
def test_live_diagnosis_on_real_fixture(context_payload):
    settings = Settings()
    service = DiagnosisService(settings)

    result = service.diagnose(IncidentContext.model_validate(context_payload))

    # 真实调用只允许两种正常语义结果；服务层失败（UNAVAILABLE）视为 smoke 失败
    assert result.diagnosis_status in {"DIAGNOSED", "INSUFFICIENT_EVIDENCE"}, result.error_code
    # 真实模型可能提交超过 max_evidence_items 的证据：这些是 over_limit（合法但被输出上限截断），
    # 不是 dropped；因此这里只要求 dropped == 0，不要求 over_limit == 0。
    assert result.evidence_validation.dropped == 0, result.evidence_validation
    assert_submitted_invariant(result.evidence_validation)
    if result.diagnosis_status == "DIAGNOSED":
        assert result.root_cause
        assert result.evidence, "DIAGNOSED 必须给出可回溯证据"


@pytest.mark.skipif(not _HAS_KEY, reason="本机未配置 DEEPSEEK_API_KEY，跳过真实调用")
def test_live_diagnosis_with_missing_redis_fixture(missing_redis_payload):
    """主证据缺失时，期望模型选择 INSUFFICIENT_EVIDENCE 而不是编造根因。"""
    settings = Settings()
    service = DiagnosisService(settings)

    result = service.diagnose(IncidentContext.model_validate(missing_redis_payload))

    assert result.diagnosis_status in {"DIAGNOSED", "INSUFFICIENT_EVIDENCE"}, result.error_code
    assert result.evidence_validation.dropped == 0, result.evidence_validation
    assert_submitted_invariant(result.evidence_validation)
    if result.diagnosis_status == "DIAGNOSED":
        assert result.evidence, "DIAGNOSED 必须给出可回溯证据"
