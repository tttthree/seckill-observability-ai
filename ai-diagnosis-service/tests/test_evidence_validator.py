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
