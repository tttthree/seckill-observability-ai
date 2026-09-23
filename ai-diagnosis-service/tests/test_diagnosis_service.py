"""诊断编排测试：LLM 只出语义字段；其余元数据由服务生成；降级矩阵逐项覆盖。"""

import logging

import pytest

from config import Settings
from conftest import StubDeepSeekClient
from models.context import IncidentContext
from models.diagnosis import ErrorCode
from services.deepseek_client import DeepSeekClient, ModelCallError
from services.diagnosis_service import (
    ContextTooLarge,
    DiagnosisService,
    PromptTooLarge,
    UnsupportedContextVersion,
)


def make_service(payload=None, error=None, raw_text=None, **overrides):
    settings = Settings(**overrides)
    client = StubDeepSeekClient(payload=payload, error=error, raw_text=raw_text)
    return DiagnosisService(settings, client=client), client, settings


GOOD_LLM = {
    "diagnosis_status": "DIAGNOSED",
    "root_cause": "券维度 Redis 库存采集中断，构建时刻的库存状态与检测证据不一致。",
    "evidence": [
        {"path": "incident.detected_snapshot.redis_stock", "note": "最近一次检测证据显示 Redis 库存为 0"},
        {"path": "redis.voucher_stock.value", "note": "构建时刻 Redis 库存已恢复为 1"},
        {"path": "database.seckill_voucher.stock", "note": "数据库库存为 1"},
    ],
    "recommended_actions": [
        {"action": "人工核对券 7001 的 Redis 与 MySQL 库存", "rationale": "系统不会自动覆盖库存"}
    ],
    "insufficient_reason": None,
}


def test_diagnosed_result_metadata_is_generated_by_service(context):
    service, client, settings = make_service(payload=GOOD_LLM)

    result = service.diagnose(context)

    assert client.calls == 1
    assert result.diagnosis_status == "DIAGNOSED"
    assert result.context_version == "v2-2.1"
    assert result.incident_id == context.incident.incident_id
    assert result.model == settings.deepseek_model
    assert result.prompt_version == "v2-3.1"
    assert result.error_code is None
    assert result.diagnosed_at.tzinfo is not None
    assert result.elapsed_ms >= 0
    # observed 由服务按 Context 真实值回填
    observed = {e.path: e.observed for e in result.evidence}
    assert observed["redis.voucher_stock.value"] == 1
    assert observed["database.seckill_voucher.stock"] == 1
    assert result.evidence_validation.submitted == 3
    assert result.evidence_validation.accepted == 3
    assert result.evidence_validation.dropped == 0


def test_action_requires_human_is_forced_by_service(context):
    """模型即使给出 requires_human=false，也必须被服务覆盖为 true。"""
    payload = {
        "diagnosis_status": "DIAGNOSED",
        "root_cause": "x",
        "evidence": [{"path": "incident.status", "note": "n"}],
        "recommended_actions": [
            {"action": "自动回滚库存", "rationale": "看起来更快", "requires_human": False}
        ],
        "insufficient_reason": None,
    }
    service, _, _ = make_service(payload=payload)

    result = service.diagnose(context)

    assert result.recommended_actions[0].requires_human is True


def test_actions_are_capped(context):
    payload = dict(GOOD_LLM)
    payload["recommended_actions"] = [
        {"action": f"a{i}", "rationale": "r"} for i in range(9)
    ]
    service, _, settings = make_service(payload=payload)

    result = service.diagnose(context)

    assert len(result.recommended_actions) == settings.max_actions


def test_diagnosed_with_bogus_paths_is_downgraded(context):
    payload = {
        "diagnosis_status": "DIAGNOSED",
        "root_cause": "看似有根因",
        "evidence": [{"path": "redis.voucher_stock.invented_field", "note": "编造"}],
        "recommended_actions": [],
        "insufficient_reason": None,
    }
    service, _, _ = make_service(payload=payload)

    result = service.diagnose(context)

    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert result.root_cause is None
    assert result.insufficient_reason
    assert result.evidence == []
    assert result.evidence_validation.dropped == 1


def test_diagnosed_without_root_cause_is_downgraded(context):
    payload = dict(GOOD_LLM)
    payload["root_cause"] = "  "
    service, _, _ = make_service(payload=payload)

    result = service.diagnose(context)

    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert result.insufficient_reason


def test_insufficient_evidence_status_is_kept(context):
    payload = {
        "diagnosis_status": "INSUFFICIENT_EVIDENCE",
        "root_cause": None,
        "evidence": [],
        "recommended_actions": [],
        "insufficient_reason": "缺少券维度 Redis 采集证据",
    }
    service, _, _ = make_service(payload=payload)

    result = service.diagnose(context)

    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert result.insufficient_reason == "缺少券维度 Redis 采集证据"
    assert result.error_code is None


# ==================== V2-3.1：INSUFFICIENT_EVIDENCE 统一正规化 ====================


def test_insufficient_evidence_strips_root_cause_and_actions(context):
    """模型即使在 INSUFFICIENT_EVIDENCE 中给出 root_cause/actions，也不得进入最终结果。"""
    payload = {
        "diagnosis_status": "INSUFFICIENT_EVIDENCE",
        "root_cause": "模型自作主张的根因",
        "evidence": [{"path": "incident.status", "note": "事件状态"}],
        "recommended_actions": [{"action": "重启消费者", "rationale": "猜测"}],
        "insufficient_reason": "缺少 Redis 证据",
    }
    service, _, _ = make_service(payload=payload)

    result = service.diagnose(context)

    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert result.root_cause is None
    assert result.recommended_actions == []
    assert result.error_code is None
    # 已通过回校验的 evidence 允许保留
    assert [e.path for e in result.evidence] == ["incident.status"]
    assert result.insufficient_reason == "缺少 Redis 证据"


def test_downgraded_insufficient_evidence_is_normalized(context):
    """由 DIAGNOSED 降级而来时同样必须清空 root_cause 与 actions。"""
    payload = {
        "diagnosis_status": "DIAGNOSED",
        "root_cause": "看似有根因",
        "evidence": [{"path": "redis.invented", "note": "编造"}],
        "recommended_actions": [{"action": "自动回滚库存", "rationale": "猜测"}],
        "insufficient_reason": None,
    }
    service, _, _ = make_service(payload=payload)

    result = service.diagnose(context)

    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert result.root_cause is None
    assert result.recommended_actions == []
    assert result.error_code is None
    assert result.insufficient_reason


def test_incident_missing_skips_model_and_returns_stable_result(context_payload):
    """incident=null：不调用模型，返回稳定的 INSUFFICIENT_EVIDENCE。"""
    payload = dict(context_payload)
    payload["incident"] = None
    context_without_incident = IncidentContext.model_validate(payload)
    service, client, _ = make_service(payload=GOOD_LLM)

    result = service.diagnose(context_without_incident)

    assert client.calls == 0, "Incident 主证据缺失时禁止调用模型"
    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert result.incident_id is None
    assert result.incident_type is None
    assert result.root_cause is None
    assert result.evidence == []
    assert result.recommended_actions == []
    assert result.error_code is None
    assert result.insufficient_reason == "Incident 主证据不可用，无法进行事件级诊断"
    assert result.evidence_validation.submitted == 0


def test_incident_id_is_real_value_not_sentinel(context):
    """正常事件仍返回真实 id，禁止把 0 当哨兵。"""
    service, _, _ = make_service(payload=GOOD_LLM)

    result = service.diagnose(context)

    assert result.incident_id == context.incident.incident_id
    assert result.incident_id != 0


def test_insufficient_evidence_gets_default_reason(context):
    payload = {
        "diagnosis_status": "INSUFFICIENT_EVIDENCE",
        "evidence": [],
        "recommended_actions": [],
    }
    service, _, _ = make_service(payload=payload)

    result = service.diagnose(context)

    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert result.insufficient_reason


def test_markdown_fenced_output_is_cleaned(context):
    import json as _json

    service, client, _ = make_service(raw_text="```json\n" + _json.dumps(GOOD_LLM) + "\n```")

    result = service.diagnose(context)

    assert result.diagnosis_status == "DIAGNOSED"
    assert client.calls == 1


def test_invalid_model_json_degrades_without_retry(context):
    service, client, _ = make_service(raw_text="这不是 JSON")

    result = service.diagnose(context)

    assert result.diagnosis_status == "UNAVAILABLE"
    assert result.error_code == ErrorCode.MODEL_OUTPUT_INVALID.value
    assert client.calls == 1, "V2-3 不做重试"


# ==================== V2-3.2：模型输出不合规时的安全日志 ====================


def test_validation_error_log_contains_only_loc_and_type(context, caplog):
    """Pydantic ValidationError 只记录 loc / type，不泄露模型原文与 input。"""
    secret_marker = "SECRET-MODEL-OUTPUT-不得到日志"
    # evidence 类型错误（应为 list），同时带上一个敏感标记值
    raw = "{\"diagnosis_status\":\"DIAGNOSED\",\"root_cause\":\"" + secret_marker + "\",\"evidence\":\"not-a-list\"}"

    service, client, _ = make_service(raw_text=raw)
    with caplog.at_level(logging.WARNING, logger="services.diagnosis_service"):
        result = service.diagnose(context)

    assert result.diagnosis_status == "UNAVAILABLE"
    assert result.error_code == ErrorCode.MODEL_OUTPUT_INVALID.value
    assert client.calls == 1

    logs = caplog.text
    assert "diagnosis model output invalid" in logs
    assert "'loc'" in logs and "'type'" in logs
    # 禁止出现：模型完整原文、input、IncidentContext 内容
    assert secret_marker not in logs
    assert "not-a-list" not in logs
    assert "'input'" not in logs
    assert "detected_snapshot" not in logs
    assert "voucher:7001" not in logs


def test_validation_error_log_lists_each_error(context, caplog):
    """多个字段不合规时，逐条记录 loc/type。"""
    raw = (
        '{"diagnosis_status":"NOT_A_STATUS","evidence":[{"note":"缺少 path"}],'
        '"recommended_actions":[{"action":"a"}],"root_cause":123}'
    )
    service, _, _ = make_service(raw_text=raw)
    with caplog.at_level(logging.WARNING, logger="services.diagnosis_service"):
        result = service.diagnose(context)

    assert result.error_code == ErrorCode.MODEL_OUTPUT_INVALID.value
    logs = caplog.text
    assert "error_count=" in logs
    assert logs.count("'loc'") >= 2, logs


def test_timeout_degrades(context):
    service, client, _ = make_service(error=ModelCallError(ErrorCode.MODEL_TIMEOUT, "timeout"))

    result = service.diagnose(context)

    assert result.diagnosis_status == "UNAVAILABLE"
    assert result.error_code == ErrorCode.MODEL_TIMEOUT.value
    assert client.calls == 1


@pytest.mark.parametrize(
    "code",
    [
        ErrorCode.MODEL_UNREACHABLE,
        ErrorCode.MODEL_RATE_LIMITED,
        ErrorCode.MODEL_HTTP_ERROR,
        ErrorCode.MODEL_AUTH_ERROR,
    ],
)
def test_model_failures_map_to_unavailable(context, code):
    service, _, _ = make_service(error=ModelCallError(code, "x"))

    result = service.diagnose(context)

    assert result.diagnosis_status == "UNAVAILABLE"
    assert result.error_code == code.value


def test_missing_api_key_degrades_without_calling_model(context):
    """未配置 Key：真实客户端不会发起请求，返回 UNAVAILABLE。"""
    settings = Settings(deepseek_api_key="")
    service = DiagnosisService(settings, client=DeepSeekClient(settings))

    result = service.diagnose(context)

    assert result.diagnosis_status == "UNAVAILABLE"
    assert result.error_code == ErrorCode.MODEL_NOT_CONFIGURED.value


def test_unsupported_context_version_is_rejected(context):
    service, client, _ = make_service(payload=GOOD_LLM, supported_context_versions="v9-9.9")

    with pytest.raises(UnsupportedContextVersion):
        service.diagnose(context)
    assert client.calls == 0, "版本不匹配时不得调用模型"


def test_context_too_large_is_rejected(context):
    service, client, _ = make_service(payload=GOOD_LLM, max_context_chars=50)

    with pytest.raises(ContextTooLarge):
        service.diagnose(context)
    assert client.calls == 0


def test_prompt_too_large_is_rejected(context):
    service, client, _ = make_service(payload=GOOD_LLM, max_context_chars=200_000, max_prompt_chars=50)

    with pytest.raises(PromptTooLarge):
        service.diagnose(context)
    assert client.calls == 0


def test_prompt_sent_to_model_has_no_notes(context):
    service, client, _ = make_service(payload=GOOD_LLM)

    service.diagnose(context)

    assert client.last_prompt is not None
    for note in context.context_quality.notes:
        assert note not in client.last_prompt


def test_missing_redis_context_can_still_be_diagnosed_as_insufficient(missing_redis_payload):
    context = IncidentContext.model_validate(missing_redis_payload)
    payload = {
        "diagnosis_status": "INSUFFICIENT_EVIDENCE",
        "evidence": [],
        "recommended_actions": [],
        "insufficient_reason": "redis 数据源不可用，缺少库存证据",
    }
    service, _, _ = make_service(payload=payload)

    result = service.diagnose(context)

    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert result.context_version == "v2-2.1"
