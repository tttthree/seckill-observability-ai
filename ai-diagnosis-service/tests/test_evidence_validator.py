"""evidence path 回校验测试：冻结语法 + 真实值回填。"""

import pytest

from models.diagnosis import LLMEvidence
from services.evidence_validator import (
    PathNotFound,
    PathSyntaxError,
    parse_path,
    resolve_path,
    validate_evidence,
)

CONTEXT = {
    "incident": {"status": "RESOLVED", "detected_snapshot": {"redis_stock": 0, "deviation": 1}},
    "metrics": {"counters": {"total_requests": 2.0}, "counter_presence": {"consume_error": False}},
    "redis": None,
    "queue": {
        "dead_letter_entries_for_voucher": [
            {"failure_reason": "retry_exhausted", "order_id": 99},
            {"failure_reason": "other", "order_id": 100},
        ]
    },
    "context_quality": {"available_sources": ["incident", "metrics"]},
}


@pytest.mark.parametrize(
    ("path", "expected"),
    [
        ("incident.status", "RESOLVED"),
        ("metrics.counters.total_requests", 2.0),
        ("metrics.counter_presence.consume_error", False),
        ("incident.detected_snapshot.redis_stock", 0),
        ("queue.dead_letter_entries_for_voucher[0].failure_reason", "retry_exhausted"),
        ("queue.dead_letter_entries_for_voucher[1].order_id", 100),
        ("context_quality.available_sources", ["incident", "metrics"]),
        ("redis", None),
    ],
)
def test_resolve_path_supports_dot_and_index(path, expected):
    assert resolve_path(CONTEXT, path) == expected


@pytest.mark.parametrize(
    "path",
    ["", "   ", "incident..status", "incident[status]", "incident[-1]", "incident[", "incident[0]x", "[0]"],
)
def test_invalid_syntax_is_rejected(path):
    with pytest.raises(PathSyntaxError):
        parse_path(path)


@pytest.mark.parametrize(
    "path",
    [
        "incident.missing_field",
        "queue.dead_letter_entries_for_voucher[5]",
        "incident[0]",
        "queue.dead_letter_entries_for_voucher[0].missing",
        "redis.voucher_stock",
    ],
)
def test_missing_paths_are_not_found(path):
    with pytest.raises(PathNotFound):
        resolve_path(CONTEXT, path)


def test_observed_comes_from_context_not_from_model():
    """模型的 note 保留，observed 一律回填 Context 真实值。"""
    items = [LLMEvidence(path="incident.status", note="事件已恢复")]
    accepted, validation = validate_evidence(CONTEXT, items, max_items=10)
    assert len(accepted) == 1
    assert accepted[0].observed == "RESOLVED"
    assert accepted[0].note == "事件已恢复"
    assert validation.submitted == 1 and validation.accepted == 1 and validation.dropped == 0


def test_indexed_evidence_observed_is_real_element_value():
    items = [LLMEvidence(path="queue.dead_letter_entries_for_voucher[1].order_id", note="第二条死信")]
    accepted, _ = validate_evidence(CONTEXT, items, max_items=10)
    assert accepted[0].observed == 100


def test_invalid_paths_are_dropped_and_counted():
    items = [
        LLMEvidence(path="incident.status", note="ok"),
        LLMEvidence(path="incident.not_exist", note="bogus"),
        LLMEvidence(path="incident..bad", note="syntax"),
        LLMEvidence(path="queue.dead_letter_entries_for_voucher[9].order_id", note="oor"),
    ]
    accepted, validation = validate_evidence(CONTEXT, items, max_items=10)
    assert [e.path for e in accepted] == ["incident.status"]
    assert validation.submitted == 4 and validation.accepted == 1 and validation.dropped == 3


def test_duplicate_paths_are_deduped():
    items = [
        LLMEvidence(path="incident.status", note="first"),
        LLMEvidence(path="incident.status", note="second"),
    ]
    accepted, validation = validate_evidence(CONTEXT, items, max_items=10)
    assert len(accepted) == 1
    assert accepted[0].note == "first"
    assert validation.dropped == 1


def test_evidence_items_are_capped():
    """上限按"可回溯的不同证据"计算；超出部分计入 dropped。"""
    big_context = {"items": [{"id": i} for i in range(15)]}
    items = [LLMEvidence(path=f"items[{i}].id", note=f"n{i}") for i in range(15)]
    accepted, validation = validate_evidence(big_context, items, max_items=3)
    assert [e.observed for e in accepted] == [0, 1, 2]
    assert validation.submitted == 15 and validation.accepted == 3 and validation.dropped == 12


# ==================== V2-3.1：counter_presence 由代码强制 ====================

COUNTER_CONTEXT = {
    "metrics": {
        "counters": {"total_requests": 0, "consume_error": 0},
        "counter_presence": {"total_requests": True, "consume_error": False},
    }
}


def test_counter_evidence_accepted_when_presence_true_even_if_value_zero():
    """presence=true + counter=0 → 可接受（0 是真实观测值）。"""
    items = [LLMEvidence(path="metrics.counters.total_requests", note="零请求")]
    accepted, validation = validate_evidence(COUNTER_CONTEXT, items, max_items=10)

    assert len(accepted) == 1
    assert accepted[0].observed == 0
    assert validation.submitted == 1 and validation.accepted == 1 and validation.dropped == 0


def test_counter_evidence_dropped_when_presence_false():
    """presence=false + counter=0 → 必须 dropped（不得只依赖 prompt 约束）。"""
    items = [LLMEvidence(path="metrics.counters.consume_error", note="看起来是 0")]
    accepted, validation = validate_evidence(COUNTER_CONTEXT, items, max_items=10)

    assert accepted == []
    assert validation.submitted == 1 and validation.accepted == 0 and validation.dropped == 1


def test_counter_evidence_dropped_when_presence_missing():
    """counter 存在但 counter_presence 中没有同名条目 → dropped。"""
    context = {"metrics": {"counters": {"orphan": 3}, "counter_presence": {}}}
    items = [LLMEvidence(path="metrics.counters.orphan", note="缺 presence 条目")]
    accepted, validation = validate_evidence(context, items, max_items=10)

    assert accepted == []
    assert validation.dropped == 1


def test_counter_evidence_dropped_when_presence_unresolvable():
    """metrics 缺失 / counter_presence 结构不可解析 → dropped。"""
    no_metrics = {"metrics": None}
    items = [LLMEvidence(path="metrics.counters.total_requests", note="x")]
    accepted, validation = validate_evidence(no_metrics, items, max_items=10)

    assert accepted == []
    assert validation.dropped == 1


def test_counter_presence_gate_mixed_statistics():
    """混合场景下的 submitted/accepted/dropped 统计。"""
    items = [
        LLMEvidence(path="metrics.counters.total_requests", note="presence=true"),
        LLMEvidence(path="metrics.counters.consume_error", note="presence=false"),
        LLMEvidence(path="metrics.counters.not_exist", note="counter 不存在"),
        LLMEvidence(path="metrics.counter_presence.total_requests", note="presence 本身可引用"),
    ]
    accepted, validation = validate_evidence(COUNTER_CONTEXT, items, max_items=10)

    assert [e.path for e in accepted] == [
        "metrics.counters.total_requests",
        "metrics.counter_presence.total_requests",
    ]
    assert validation.submitted == 4
    assert validation.accepted == 2
    assert validation.dropped == 2


def test_non_counter_paths_are_unaffected_by_presence_gate():
    items = [LLMEvidence(path="metrics.counter_presence.consume_error", note="presence=false")]
    accepted, validation = validate_evidence(COUNTER_CONTEXT, items, max_items=10)

    assert len(accepted) == 1, "非 metrics.counters.* 路径不受 presence 闸门约束"
    assert accepted[0].observed is False
    assert validation.dropped == 0


def test_counter_presence_gate_on_real_fixture(context_payload):
    """真实（脱敏）fixture：presence=true 的计数器可接受，presence=false 的必须 dropped。"""
    from models.context import IncidentContext

    context_json = IncidentContext.model_validate(context_payload).model_dump(mode="json")
    items = [
        LLMEvidence(path="metrics.counters.total_requests", note="presence=true"),
        LLMEvidence(path="metrics.counters.consume_error", note="presence=false"),
    ]
    accepted, validation = validate_evidence(context_json, items, max_items=10)

    assert [e.path for e in accepted] == ["metrics.counters.total_requests"]
    assert validation.accepted == 1 and validation.dropped == 1
