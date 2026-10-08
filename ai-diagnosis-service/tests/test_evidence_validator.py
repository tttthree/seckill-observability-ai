"""evidence path 回校验测试：冻结语法 + 真实值回填（v3.0 契约）。

metrics / counter_presence 已随 v3.0 从 Context 删除，因此不再有对应的特殊闸门。
"""

import pytest

from conftest import assert_submitted_invariant
from models.diagnosis import LLMEvidence
from services.evidence_validator import (
    PathNotFound,
    PathSyntaxError,
    parse_path,
    resolve_path,
    validate_evidence,
)
CONTEXT = {
    "incident": {"status": "OPEN", "detected_snapshot": {"redis_stock": None, "deviation": None}},
    "redis": {"stock": {"present": False, "value": None}, "dirty": True, "mismatch_pending": True},
    "database": {"voucher_exists": True, "stock": 0, "order_count": 2},
    "queue": {
        "pending_count": 3,
        "dead_letters": [
            {"failure_reason": "retry_exhausted", "order_id": 99},
            {"failure_reason": "other", "order_id": 100},
        ],
    },
    "consumer_health": None,
    "unavailable_sources": [],
}


@pytest.mark.parametrize(
    ("path", "expected"),
    [
        ("incident.status", "OPEN"),
        ("incident.detected_snapshot.redis_stock", None),
        ("redis.stock.present", False),
        ("redis.stock.value", None),
        ("redis.dirty", True),
        ("database.stock", 0),
        ("queue.pending_count", 3),
        ("queue.dead_letters[0].failure_reason", "retry_exhausted"),
        ("queue.dead_letters[1].order_id", 100),
        ("unavailable_sources", []),
        ("consumer_health", None),
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
        "queue.dead_letters[5]",
        "incident[0]",
        "queue.dead_letters[0].missing",
        "redis.voucher_stock",
        "metrics.counters.total_requests",
        "context_quality.complete",
    ],
)
def test_missing_paths_are_not_found(path):
    with pytest.raises(PathNotFound):
        resolve_path(CONTEXT, path)


def test_observed_comes_from_context_not_from_model():
    """模型的 note 保留，observed 一律回填 Context 真实值。"""
    items = [LLMEvidence(path="incident.status", note="事件仍开启")]
    accepted, validation = validate_evidence(CONTEXT, items, max_items=10)
    assert len(accepted) == 1
    assert accepted[0].observed == "OPEN"
    assert accepted[0].note == "事件仍开启"
    assert validation.submitted == 1 and validation.accepted == 1 and validation.dropped == 0
    assert validation.over_limit == 0
    assert_submitted_invariant(validation)


def test_indexed_evidence_observed_is_real_element_value():
    items = [LLMEvidence(path="queue.dead_letters[1].order_id", note=None)]
    accepted, validation = validate_evidence(CONTEXT, items, max_items=10)
    assert accepted[0].observed == 100
    assert_submitted_invariant(validation)


def test_null_valued_path_is_accepted_with_none_observed():
    """路径存在但值为 null 时必须 accepted（这是 missing≠0 的核心证据），observed=None。"""
    for path in ("redis.stock.value", "incident.detected_snapshot.redis_stock"):
        accepted, validation = validate_evidence(
            CONTEXT, [LLMEvidence(path=path, note=None)], max_items=10
        )
        assert len(accepted) == 1, path
        assert accepted[0].observed is None, path
        assert validation.dropped == 0, path
        assert_submitted_invariant(validation)


def test_invalid_paths_are_dropped_and_counted():
    items = [
        LLMEvidence(path="incident.status", note=None),
        LLMEvidence(path="incident.nope", note=None),
        LLMEvidence(path="not a path", note=None),
    ]
    accepted, validation = validate_evidence(CONTEXT, items, max_items=10)
    assert len(accepted) == 1
    assert validation.submitted == 3
    assert validation.accepted == 1
    assert validation.dropped == 2
    assert validation.over_limit == 0
    assert_submitted_invariant(validation)


def test_duplicate_paths_are_deduped():
    items = [
        LLMEvidence(path="incident.status", note="a"),
        LLMEvidence(path="incident.status", note="b"),
    ]
    accepted, validation = validate_evidence(CONTEXT, items, max_items=10)
    assert len(accepted) == 1
    assert accepted[0].note == "a"
    assert validation.dropped == 1
    assert_submitted_invariant(validation)


def test_evidence_items_are_capped():
    items = [LLMEvidence(path="queue.pending_count", note=None)] * 1
    accepted, validation = validate_evidence(CONTEXT, items, max_items=1)
    assert len(accepted) == 1
    assert validation.over_limit == 0
    assert_submitted_invariant(validation)


def test_valid_items_beyond_cap_are_over_limit_not_dropped():
    items = [
        LLMEvidence(path="incident.status", note=None),
        LLMEvidence(path="redis.dirty", note=None),
        LLMEvidence(path="redis.mismatch_pending", note=None),
    ]
    accepted, validation = validate_evidence(CONTEXT, items, max_items=1)
    assert len(accepted) == 1
    assert validation.submitted == 3
    assert validation.accepted == 1
    assert validation.dropped == 0
    assert validation.over_limit == 2
    assert_submitted_invariant(validation)


def test_invalid_item_beyond_cap_is_still_dropped():
    items = [
        LLMEvidence(path="incident.status", note=None),
        LLMEvidence(path="incident.nope", note=None),
    ]
    accepted, validation = validate_evidence(CONTEXT, items, max_items=1)
    assert len(accepted) == 1
    assert validation.dropped == 1
    assert validation.over_limit == 0
    assert_submitted_invariant(validation)


def test_duplicate_beyond_cap_is_dropped_not_over_limit():
    items = [
        LLMEvidence(path="incident.status", note=None),
        LLMEvidence(path="incident.status", note=None),
        LLMEvidence(path="redis.dirty", note=None),
    ]
    accepted, validation = validate_evidence(CONTEXT, items, max_items=1)
    assert len(accepted) == 1
    assert validation.dropped == 1
    assert validation.over_limit == 1
    assert_submitted_invariant(validation)


@pytest.mark.parametrize(("item_count", "max_items"), [(0, 1), (1, 0), (5, 3), (10, 10), (3, 99)])
def test_submitted_invariant_holds_across_caps(item_count, max_items):
    pool = [
        "incident.status",
        "redis.stock.present",
        "redis.stock.value",
        "database.stock",
        "queue.pending_count",
        "incident.nope",
        "bogus path",
    ]
    items = [LLMEvidence(path=pool[i % len(pool)], note=None) for i in range(item_count)]
    _, validation = validate_evidence(CONTEXT, items, max_items=max_items)
    assert_submitted_invariant(validation)


# ==================== 真实 fixture ====================


def test_paths_resolve_on_real_fixture(context_payload):
    """回校验只在真实 Context 上解析 path，v3.0 新路径必须可解析。"""
    context = context_payload
    for path, expected in (
        ("incident.status", "OPEN"),
        ("redis.stock.value", 1),
        ("redis.stock.present", True),
        ("redis.ordered_user_count", 2),
        ("redis.dirty", True),
        ("redis.mismatch_pending", True),
        ("database.voucher_exists", True),
        ("database.stock", 2),
        ("database.order_count", 2),
        ("database.recent_orders[0].order_id", 810000000000000001),
        ("incident.detected_snapshot.redis_stock", 1),
    ):
        assert resolve_path(context, path) == expected, path


def test_dead_letter_fixture_paths_resolve(dead_letter_payload):
    assert resolve_path(dead_letter_payload, "queue.dead_letter_count") == 1
    assert resolve_path(dead_letter_payload, "queue.dead_letters[0].message_id") == "1768465200000-0"
    assert resolve_path(dead_letter_payload, "consumer_health.status") == "HEALTHY"


def test_missing_stock_fixture_null_paths_are_accepted(missing_stock_payload):
    accepted, validation = validate_evidence(
        missing_stock_payload,
        [
            LLMEvidence(path="redis.stock.present", note="key 不存在"),
            LLMEvidence(path="redis.stock.value", note="不得当作 0"),
        ],
        max_items=10,
    )
    assert [item.observed for item in accepted] == [False, None]
    assert validation.dropped == 0
    assert_submitted_invariant(validation)


def test_runbook_named_paths_still_do_not_become_evidence(context):
    """runbook 内容没有 path，任何 runbook 相关 path 都不可解析。"""
    context_json = context.model_dump(mode="json")
    accepted, validation = validate_evidence(
        context_json,
        [LLMEvidence(path="runbook.checks", note=None)],
        max_items=10,
    )
    assert accepted == []
    assert validation.dropped == 1
    assert_submitted_invariant(validation)
