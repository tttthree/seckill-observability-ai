"""V2-6 claim-level grounding 测试：citation 重排、root_cause grounding、action grounding、日志边界。"""

import json
import logging

import pytest

from config import Settings
from conftest import StubDeepSeekClient, assert_submitted_invariant
from models.diagnosis import LLMAction, LLMEvidence
from services.claim_grounding import (
    collect_action_paths,
    evaluate_root_grounding,
    filter_grounded_actions,
    prioritize_evidence,
    unique_paths,
)
from services.diagnosis_service import DiagnosisService

# fixture（incident_context_v2_2_1.json）中真实存在且能通过回校验的 path
VALID_PATHS = [
    "incident.status",
    "incident.incident_type",
    "incident.incident_id",
    "incident.severity",
    "incident.source",
    "incident.business_key",
    "incident.related_voucher_id",
    "incident.occurrence_count",
    "incident.title",
    "incident.description",
    "incident.detected_snapshot.redis_stock",
    "incident.detected_snapshot.db_stock",
    "incident.detected_snapshot.deviation",
    "redis.voucher_stock.value",
    "redis.voucher_stock.present",
    "database.seckill_voucher.stock",
    "database.order_count_for_voucher",
    "metrics.counters.total_requests",
    "context_quality.complete",
]

# presence=false → 无论数值如何都不能通过回校验（V2-5.1 语义）
NOT_GROUNDABLE_PATH = "metrics.counters.consume_error"


def make_service(payload, **overrides):
    settings = Settings(**overrides)
    client = StubDeepSeekClient(payload=payload)
    return DiagnosisService(settings, client=client), client, settings


def diagnosed_payload(evidence_paths, root_citations, actions=(), root_cause="当前两端库存一致，事件已 RESOLVED。"):
    return {
        "diagnosis_status": "DIAGNOSED",
        "root_cause": root_cause,
        "root_cause_evidence_paths": list(root_citations),
        "evidence": [{"path": path, "note": None} for path in evidence_paths],
        "recommended_actions": list(actions),
        "insufficient_reason": None,
    }


# ==================== 单元层：重排 / 计数 / action 过滤 ====================


def test_prioritize_evidence_is_a_stable_permutation():
    items = [LLMEvidence(path=f"p{index}") for index in range(6)]

    ordered = prioritize_evidence(items, ["p4", "p1"], ["p3"])

    assert [item.path for item in ordered] == ["p1", "p4", "p3", "p0", "p2", "p5"]
    # 不新增、不删除、不去重：只是同一批元素换顺序
    assert sorted(item.path for item in ordered) == sorted(item.path for item in items)


def test_overlapping_citation_prefers_root_bucket():
    items = [LLMEvidence(path="a"), LLMEvidence(path="b")]

    ordered = prioritize_evidence(items, ["b"], ["a", "b"])

    assert [item.path for item in ordered] == ["b", "a"]


def test_unique_paths_keeps_first_occurrence_order():
    assert unique_paths(["b", "a", "b", "c", "a"]) == ["b", "a", "c"]


def test_root_grounding_counts_dedupe_citations():
    cited, accepted, invalid = evaluate_root_grounding(["a", "a", "b"], {"a"})

    assert (cited, accepted, invalid) == (2, 1, 1)


def test_collect_action_paths_preserves_order():
    actions = [
        LLMAction(action="a", rationale="r", evidence_paths=["p1", "p2"]),
        LLMAction(action="b", rationale="r", evidence_paths=["p3"]),
    ]

    assert collect_action_paths(actions) == ["p1", "p2", "p3"]


def test_action_without_valid_citation_is_dropped():
    actions = [
        LLMAction(action="keep-1", rationale="r", evidence_paths=["incident.status"]),
        LLMAction(action="drop-no-citation", rationale="r", evidence_paths=[]),
        LLMAction(action="drop-runbook", rationale="r", evidence_paths=["rb-inventory-mismatch"]),
        LLMAction(action="keep-2", rationale="r", evidence_paths=["nope", "incident.status"]),
    ]

    kept, dropped = filter_grounded_actions(actions, {"incident.status"}, max_actions=5)

    assert [action.action for action in kept] == ["keep-1", "keep-2"]
    assert dropped == 2


def test_max_actions_is_applied_before_grounding_filter():
    actions = [
        LLMAction(action=f"a{index}", rationale="r", evidence_paths=["incident.status"])
        for index in range(7)
    ]

    kept, dropped = filter_grounded_actions(actions, {"incident.status"}, max_actions=5)

    assert [action.action for action in kept] == ["a0", "a1", "a2", "a3", "a4"]
    assert dropped == 0


# ==================== 服务层：root_cause grounding ====================


def test_diagnosed_with_grounded_root_cause_passes(context):
    payload = diagnosed_payload(
        ["incident.status", "redis.voucher_stock.value"],
        ["incident.status"],
        actions=[{"action": "人工核对库存", "rationale": "两端不一致", "evidence_paths": ["incident.status"]}],
    )
    service, client, _ = make_service(payload)

    result = service.diagnose(context)

    assert client.calls == 1
    assert result.diagnosis_status == "DIAGNOSED"
    assert result.root_cause
    assert [item.path for item in result.evidence] == ["incident.status", "redis.voucher_stock.value"]
    assert [action.action for action in result.recommended_actions] == ["人工核对库存"]
    assert result.recommended_actions[0].requires_human is True
    assert_submitted_invariant(result.evidence_validation)


def test_diagnosed_without_root_citations_is_downgraded(context):
    payload = diagnosed_payload(["incident.status"], [], actions=[])

    service, client, _ = make_service(payload)
    result = service.diagnose(context)

    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert result.root_cause is None
    assert result.recommended_actions == []
    assert result.error_code is None
    assert result.insufficient_reason
    # 已通过回校验的 evidence 仍保留
    assert [item.path for item in result.evidence] == ["incident.status"]
    assert client.calls == 1


@pytest.mark.parametrize(
    "citation",
    [
        "incident.not_exist",  # Context 中不存在
        "rb-inventory-mismatch.summary",  # runbook 概念路径
        NOT_GROUNDABLE_PATH,  # counter_presence=false → 回校验丢弃
    ],
)
def test_diagnosed_with_only_invalid_root_citations_is_downgraded(context, citation):
    evidence_paths = ["incident.status"]
    if citation == NOT_GROUNDABLE_PATH:
        evidence_paths.append(NOT_GROUNDABLE_PATH)
    payload = diagnosed_payload(evidence_paths, [citation], actions=[])

    service, _, _ = make_service(payload)
    result = service.diagnose(context)

    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert result.root_cause is None
    assert result.recommended_actions == []


def test_partially_valid_root_citations_keep_diagnosed(context, caplog):
    payload = diagnosed_payload(
        ["incident.status", NOT_GROUNDABLE_PATH],
        ["incident.status", NOT_GROUNDABLE_PATH],
        actions=[],
    )
    service, _, _ = make_service(payload)

    with caplog.at_level(logging.INFO):
        result = service.diagnose(context)

    assert result.diagnosis_status == "DIAGNOSED"
    grounding = [r.getMessage() for r in caplog.records if "diagnosis grounding" in r.getMessage()]
    assert grounding, "必须记录 grounding 计数"
    assert "root_cited=2" in grounding[0]
    assert "root_accepted=1" in grounding[0]
    assert "invalid=1" in grounding[0]


# ==================== 服务层：重排不改变四统计（V2-3.3 冻结） ====================


def test_reordering_preserves_counts_and_promotes_cited_evidence(context):
    evidence_paths = VALID_PATHS[:15]
    cited = evidence_paths[12:15]  # 不重排时这 3 条会落进 over_limit
    payload = diagnosed_payload(evidence_paths, cited, actions=[])

    service, _, _ = make_service(payload)
    result = service.diagnose(context)

    validation = result.evidence_validation
    assert result.diagnosis_status == "DIAGNOSED"
    assert (validation.submitted, validation.accepted, validation.dropped, validation.over_limit) == (
        15, 10, 0, 5,
    ), "重排不得改变四统计语义（真实 15/10/0/5 场景）"
    assert_submitted_invariant(validation)
    assert len(result.evidence) == validation.accepted
    accepted_paths = {item.path for item in result.evidence}
    assert set(cited) <= accepted_paths, "被 root_cause 引用的证据必须进入最终 accepted evidence"


def test_reordering_keeps_duplicate_semantics(context):
    # 重复 path 仍然只保留第一条、其余计入 dropped（顺序变化不改变计数）
    payload = diagnosed_payload(
        ["incident.status", "incident.status", "redis.voucher_stock.value"],
        ["incident.status"],
        actions=[],
    )

    service, _, _ = make_service(payload)
    result = service.diagnose(context)

    validation = result.evidence_validation
    assert (validation.submitted, validation.accepted, validation.dropped, validation.over_limit) == (
        3, 2, 1, 0,
    )
    assert_submitted_invariant(validation)


# ==================== 服务层：action grounding ====================


def test_action_with_invalid_citations_is_dropped_not_downgraded(context, caplog):
    payload = diagnosed_payload(
        ["incident.status"],
        ["incident.status"],
        actions=[
            {"action": "保留：引用有效证据", "rationale": "r", "evidence_paths": ["incident.status"]},
            {"action": "丢弃：无 citation", "rationale": "r", "evidence_paths": []},
            {"action": "丢弃：runbook path", "rationale": "r", "evidence_paths": ["rb-consumer-down"]},
            {"action": "丢弃：presence=false", "rationale": "r", "evidence_paths": [NOT_GROUNDABLE_PATH]},
        ],
    )
    service, _, _ = make_service(payload)

    with caplog.at_level(logging.INFO):
        result = service.diagnose(context)

    assert result.diagnosis_status == "DIAGNOSED", "action 无有效 citation 只丢弃该条，不降级整份诊断"
    assert [action.action for action in result.recommended_actions] == ["保留：引用有效证据"]
    grounding = [r.getMessage() for r in caplog.records if "diagnosis grounding" in r.getMessage()]
    assert "dropped_actions=3" in grounding[0]
    assert "downgraded=False" in grounding[0]


def test_downgraded_flag_is_logged_when_grounding_fails(context, caplog):
    payload = diagnosed_payload(["incident.status"], [], actions=[])

    service, _, _ = make_service(payload)
    with caplog.at_level(logging.INFO):
        service.diagnose(context)

    grounding = [r.getMessage() for r in caplog.records if "diagnosis grounding" in r.getMessage()]
    assert "downgraded=True" in grounding[0]


# ==================== 日志边界：只记录计数 ====================


def test_grounding_log_records_counts_only(context, caplog):
    secret = "ROOT-CAUSE-TEXT-MUST-NOT-BE-LOGGED"
    payload = diagnosed_payload(["incident.status"], ["incident.status"], actions=[], root_cause=secret)
    service, _, _ = make_service(payload)

    with caplog.at_level(logging.INFO):
        result = service.diagnose(context)

    assert result.root_cause == secret  # 正文照常返回给调用方
    assert secret not in caplog.text, "grounding 日志不得包含 claim 正文"
    assert "diagnosis grounding" in caplog.text
    assert "root_cited=1" in caplog.text and "root_accepted=1" in caplog.text


def test_model_returned_insufficient_evidence_still_normalized(context, caplog):
    payload = {
        "diagnosis_status": "INSUFFICIENT_EVIDENCE",
        "root_cause": "模型自作主张的根因",
        "root_cause_evidence_paths": ["incident.status"],
        "evidence": [{"path": "incident.status", "note": None}],
        "recommended_actions": [
            {"action": "重启消费者", "rationale": "猜测", "evidence_paths": ["incident.status"]}
        ],
        "insufficient_reason": "缺少 Redis 采集证据",
    }
    service, _, _ = make_service(payload)

    with caplog.at_level(logging.INFO):
        result = service.diagnose(context)

    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert result.root_cause is None
    assert result.recommended_actions == []
    assert result.error_code is None
    assert result.insufficient_reason == "缺少 Redis 采集证据"


def test_llm_schema_tolerates_missing_citation_fields(context):
    """旧式（无 citation 字段）模型输出仍可解析，结果按 grounding 规则降级，不抛异常。"""
    payload = {
        "diagnosis_status": "DIAGNOSED",
        "root_cause": "旧式输出，没有 citation 字段",
        "evidence": [{"path": "incident.status", "note": None}],
        "recommended_actions": [{"action": "人工核对", "rationale": "r"}],
        "insufficient_reason": None,
    }
    service, _, _ = make_service(payload)

    result = service.diagnose(context)

    assert result.diagnosis_status == "INSUFFICIENT_EVIDENCE"
    assert json.loads(json.dumps(result.model_dump(mode="json")))["root_cause"] is None
