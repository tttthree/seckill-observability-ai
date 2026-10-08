"""输入契约（v3.0 冻结镜像）校验测试：extra=forbid、key 必存在、null 保真、missing≠0。"""

import copy

import pytest
from pydantic import ValidationError

from models.context import IncidentContext


def test_fixture_parses_and_keeps_contract_version(context_payload):
    context = IncidentContext.model_validate(context_payload)
    assert context.context_version == "v3.0"
    assert context.incident is not None
    assert context.incident.incident_type == "INVENTORY_MISMATCH"


def test_null_section_stays_none_not_defaulted(context_payload):
    """未计划采集的数据源为 null：必须保持 None，不能被默认成空对象/0。"""
    context = IncidentContext.model_validate(context_payload)
    assert context.queue is None
    assert context.consumer_health is None
    # 已计划的段存在
    assert context.redis is not None
    assert context.database is not None


def test_missing_redis_fixture_records_unavailable_source(missing_redis_payload):
    """Redis 读取失败：段为 null 且数据源名出现在 unavailable_sources。"""
    context = IncidentContext.model_validate(missing_redis_payload)
    assert context.redis is None
    assert context.unavailable_sources == ["redis"]
    # 其它数据源仍正常
    assert context.database is not None


def test_present_false_keeps_value_null(missing_stock_payload):
    """absent ≠ 0：stock.present=false 时 value 必须是 null（真实故障证据）。"""
    context = IncidentContext.model_validate(missing_stock_payload)
    assert context.redis is not None
    assert context.redis.stock is not None
    assert context.redis.stock.present is False
    assert context.redis.stock.value is None
    # 未采集的数据源为空列表，未计划采集的段为 None
    assert context.unavailable_sources == []


def test_voucher_absent_keeps_stock_null(context_payload):
    """券不存在时 stock 必须为 null，不得写成 0。"""
    payload = copy.deepcopy(context_payload)
    payload["database"]["voucher_exists"] = False
    payload["database"]["stock"] = None
    context = IncidentContext.model_validate(payload)
    assert context.database.voucher_exists is False
    assert context.database.stock is None


def test_missing_field_is_rejected_not_silently_nulled(context_payload):
    """字段缺失必须校验失败，不能静默变成 null。"""
    payload = copy.deepcopy(context_payload)
    del payload["incident"]["status"]
    with pytest.raises(ValidationError) as exc:
        IncidentContext.model_validate(payload)
    assert "status" in str(exc.value)


def test_missing_top_level_field_is_rejected(context_payload):
    payload = copy.deepcopy(context_payload)
    del payload["unavailable_sources"]
    with pytest.raises(ValidationError):
        IncidentContext.model_validate(payload)


def test_section_can_be_null_but_key_must_exist(context_payload):
    """段本身可为 null，但 key 必须存在（缺失即校验失败）。"""
    payload = copy.deepcopy(context_payload)
    payload["queue"] = None
    IncidentContext.model_validate(payload)  # 合法

    del payload["queue"]
    with pytest.raises(ValidationError):
        IncidentContext.model_validate(payload)


def test_removed_sections_are_now_forbidden(context_payload):
    """v3.0 已删除的段落必须以 extra=forbid 拒绝，而不是被静默忽略。"""
    for removed in ("metrics", "runtime", "context_quality"):
        payload = copy.deepcopy(context_payload)
        payload[removed] = {} if removed != "runtime" else {}
        with pytest.raises(ValidationError) as exc:
            IncidentContext.model_validate(payload)
        assert removed in str(exc.value)


def test_extra_field_is_forbidden(context_payload):
    """契约升级必须显式升版：未知字段直接拒绝。"""
    payload = copy.deepcopy(context_payload)
    payload["new_field_from_future"] = 1
    with pytest.raises(ValidationError) as exc:
        IncidentContext.model_validate(payload)
    assert "new_field_from_future" in str(exc.value)


def test_extra_nested_field_is_forbidden(context_payload):
    payload = copy.deepcopy(context_payload)
    payload["incident"]["future_field"] = "x"
    with pytest.raises(ValidationError):
        IncidentContext.model_validate(payload)


def test_removed_nested_fields_are_forbidden(context_payload):
    """已删除的嵌套字段同样不得再被接受。"""
    for section, field in (
        ("incident", "observed_at"),
        ("incident", "business_key"),
        ("incident", "detected_snapshot_scope"),
        ("redis", "voucher_stock"),
        ("queue", "main_stream"),
    ):
        payload = copy.deepcopy(context_payload)
        payload[section] = dict(payload[section] or {})
        payload[section][field] = None
        with pytest.raises(ValidationError):
            IncidentContext.model_validate(payload)


def test_datetime_semantics(context_payload):
    """built_at 为 UTC Instant；incident 时间与 recent_orders.create_time 保持 naive。"""
    context = IncidentContext.model_validate(context_payload)
    assert context.built_at.tzinfo is not None
    assert context.built_at.utcoffset().total_seconds() == 0
    assert context.incident is not None
    assert context.incident.first_detected_at.tzinfo is None
    assert context.database.recent_orders[0].create_time.tzinfo is None


def test_dead_letter_fixture_maps_queue_evidence(dead_letter_payload):
    context = IncidentContext.model_validate(dead_letter_payload)
    assert context.incident.incident_type == "DEAD_LETTER"
    assert context.queue.dead_letters[0].failure_reason == "retry_exhausted"
    # message_id 语义统一为原主 Stream 消息 id
    assert context.queue.dead_letters[0].message_id == "1768465200000-0"
    assert context.consumer_health.status == "HEALTHY"


def test_consumer_unhealthy_fixture_uses_single_status(consumer_unhealthy_payload):
    context = IncidentContext.model_validate(consumer_unhealthy_payload)
    assert context.incident.incident_type == "CONSUMER_UNHEALTHY"
    assert context.consumer_health.status == "DOWN"
    # 本轮不采集 redis / database / queue
    assert context.redis is None
    assert context.database is None
    assert context.queue is None
