"""Runbook 检索测试：类型硬过滤、打分、排序、Top-2、阈值语义与信号闭集（v3.0 契约）。"""

import json

from models.context import IncidentContext
from models.runbook import Runbook, is_known_signal
from services.runbook_loader import RunbookStore
from services.runbook_retriever import (
    TOP_K,
    RunbookRetriever,
    build_query_text,
    derive_signals,
)


def make_runbook(runbook_id, incident_types, signals=(), keywords=(), summary="摘要", checks=("检查项",)):
    return Runbook(
        id=runbook_id,
        title=f"标题-{runbook_id}",
        version="1",
        updated_at="2026-09-24",
        incident_types=list(incident_types),
        match_signals=list(signals) or None,
        keywords=list(keywords),
        summary=summary,
        checks=list(checks),
        do_not=[],
        references=[],
    )


def make_store(*runbooks, ready=True):
    return RunbookStore(
        enabled=True,
        ready=ready,
        runbooks=tuple(sorted(runbooks, key=lambda item: item.id)),
        invalid_count=0,
        reason="ok",
    )


def clone(payload: dict) -> dict:
    return json.loads(json.dumps(payload))


def consumer_context(payload: dict, **health) -> IncidentContext:
    """构造 CONSUMER_UNHEALTHY 样本；status 直接给投影后的三态。"""
    data = clone(payload)
    data["incident"]["incident_type"] = "CONSUMER_UNHEALTHY"
    data["redis"] = None
    data["database"] = None
    data["queue"] = None
    data["consumer_health"] = {
        "status": "HEALTHY",
        "heartbeat_age_ms": 100,
        "success_heartbeat_age_ms": 100,
        "pending_count": 0,
        "reason": None,
        **health,
    }
    return IncidentContext.model_validate(data)


# ==================== 类型硬过滤与打分 ====================


def test_incident_type_is_a_hard_filter(context):
    retriever = RunbookRetriever(make_store(
        make_runbook("rb-match", ["INVENTORY_MISMATCH"]),
        make_runbook("rb-other", ["DEAD_LETTER"]),
    ))

    result = retriever.retrieve(context)

    assert [item.id for item in result.runbooks] == ["rb-match"]
    assert result.candidates == 1


def test_signal_hits_score_double_and_keyword_hits_score_single(context):
    # fixture: snapshot:deviation_positive 命中（signal=2）；关键词 "库存" 命中（keyword=1）
    retriever = RunbookRetriever(make_store(
        make_runbook("rb-a", ["INVENTORY_MISMATCH"], signals=["snapshot:deviation_positive"], keywords=["库存"]),
        make_runbook("rb-b", ["INVENTORY_MISMATCH"], keywords=["库存"]),
    ))

    result = retriever.retrieve(context)
    scores = {item.id: item.score for item in result.runbooks}

    assert scores["rb-a"] == 3
    assert scores["rb-b"] == 1


def test_keyword_hits_are_capped_at_three(context):
    retriever = RunbookRetriever(make_store(
        make_runbook("rb-a", ["INVENTORY_MISMATCH"], keywords=["库存", "对账", "voucher", "deviation"]),
    ))

    result = retriever.retrieve(context)

    assert result.runbooks[0].score == 3  # 4 个命中只计 3 个


def test_order_is_score_desc_then_id_asc(context):
    retriever = RunbookRetriever(make_store(
        make_runbook("rb-z", ["INVENTORY_MISMATCH"], keywords=["库存"]),
        make_runbook("rb-a", ["INVENTORY_MISMATCH"], keywords=["库存"]),
        make_runbook("rb-top", ["INVENTORY_MISMATCH"], signals=["snapshot:deviation_positive"]),
    ))

    result = retriever.retrieve(context)

    assert [item.id for item in result.runbooks] == ["rb-top", "rb-a"]


def test_top_k_is_two(context):
    retriever = RunbookRetriever(make_store(
        make_runbook("rb-1", ["INVENTORY_MISMATCH"], signals=["snapshot:deviation_positive"]),
        make_runbook("rb-2", ["INVENTORY_MISMATCH"], keywords=["库存"]),
        make_runbook("rb-3", ["INVENTORY_MISMATCH"], keywords=["对账"]),
    ))

    result = retriever.retrieve(context)

    assert TOP_K == 2
    assert len(result.runbooks) == 2
    assert result.candidates == 3


def test_no_runbook_when_type_not_covered(context):
    retriever = RunbookRetriever(make_store(make_runbook("rb-other", ["DEAD_LETTER"])))

    result = retriever.retrieve(context)

    assert result.runbooks == ()
    assert result.candidates == 0


def test_not_ready_store_returns_nothing(context):
    retriever = RunbookRetriever(make_store(make_runbook("rb-a", ["INVENTORY_MISMATCH"]), ready=False))

    assert retriever.ready is False
    assert retriever.retrieve(context).runbooks == ()


# ==================== 信号派生（阈值严格复用项目定义） ====================


def test_derive_signals_for_real_fixture(context):
    signals = derive_signals(context)

    assert "snapshot:deviation_positive" in signals
    assert "snapshot:redis_lt_db" in signals
    assert "redis:dirty" in signals
    assert "redis:mismatch_pending" in signals
    assert "redis:stock_absent" not in signals
    # metrics / counter_presence 已删除，不得再产生任何 counter 信号
    assert not any(signal.startswith("counter_") for signal in signals)


def test_redis_stock_absent_signal_comes_from_present_false(missing_stock_payload):
    """stock key 确实不存在时才产生 stock_absent（present=false），不依赖数值大小。"""
    signals = derive_signals(IncidentContext.model_validate(missing_stock_payload))

    assert "redis:stock_absent" in signals
    assert "redis:dirty" in signals


def test_pending_high_requires_strictly_greater_than_1000(context_payload):
    at_threshold = derive_signals(consumer_context(context_payload, pending_count=1000))
    above_threshold = derive_signals(consumer_context(context_payload, pending_count=1001))

    assert "consumer:pending_high" not in at_threshold
    assert "consumer:pending_high" in above_threshold


def test_heartbeat_thresholds_are_strict(context_payload):
    at_threshold = derive_signals(consumer_context(
        context_payload, heartbeat_age_ms=30_000, success_heartbeat_age_ms=60_000))
    above_threshold = derive_signals(consumer_context(
        context_payload, heartbeat_age_ms=30_001, success_heartbeat_age_ms=60_001))

    assert "consumer:heartbeat_age_high" not in at_threshold
    assert "consumer:success_heartbeat_age_high" not in at_threshold
    assert "consumer:heartbeat_age_high" in above_threshold
    assert "consumer:success_heartbeat_age_high" in above_threshold


def test_consumer_status_signal_uses_projected_tri_state(context_payload):
    down = derive_signals(consumer_context(
        context_payload, status="DOWN", reason="消费者线程未启动"))
    degraded = derive_signals(consumer_context(context_payload, status="DEGRADED"))
    healthy = derive_signals(consumer_context(context_payload, status="HEALTHY"))

    assert "consumer:status:DOWN" in down
    assert "consumer:status:DEGRADED" in degraded
    assert "consumer:status:HEALTHY" in healthy
    # 旧的拆分信号已删除
    assert not any(signal.startswith("consumer:alive") for signal in down)
    assert not any(signal.startswith("consumer:consumer_status") for signal in degraded)


def test_dead_letter_signals(dead_letter_payload):
    signals = derive_signals(IncidentContext.model_validate(dead_letter_payload))

    assert "dead_letter:present" in signals
    assert "dead_letter:reason:retry_exhausted" in signals


def test_no_dead_letter_signal_when_only_other_vouchers_have_dead_letters(context_payload):
    """契约不含全局 XLEN：其它券有死信但当前券没有时，不得产生 dead_letter:present。"""
    data = clone(context_payload)
    data["incident"]["incident_type"] = "DEAD_LETTER"
    data["queue"] = {
        "pending_count": 0,
        "dead_letters": [],
    }

    signals = derive_signals(IncidentContext.model_validate(data))

    assert "dead_letter:present" not in signals
    assert not any(s.startswith("dead_letter:") for s in signals)


def test_every_derived_signal_is_in_the_closed_vocabulary(
        context, missing_redis_payload, context_payload, dead_letter_payload, consumer_unhealthy_payload):
    contexts = [
        context,
        IncidentContext.model_validate(missing_redis_payload),
        IncidentContext.model_validate(context_payload),
        IncidentContext.model_validate(dead_letter_payload),
        IncidentContext.model_validate(consumer_unhealthy_payload),
        consumer_context(context_payload, status="DOWN", pending_count=5000,
                         heartbeat_age_ms=99_999, success_heartbeat_age_ms=99_999),
        consumer_context(context_payload, status="DEGRADED", pending_count=1),
    ]

    for item in contexts:
        for signal in derive_signals(item):
            assert is_known_signal(signal), f"派生信号不在闭集词表内: {signal}"


def test_query_text_comes_from_context_only(context):
    text = build_query_text(context)

    assert "voucherid=7001" in text  # 已小写化
    assert "连续两轮对账确认偏差" in text
    assert text == text.lower()
