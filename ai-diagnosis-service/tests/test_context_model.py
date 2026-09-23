"""输入契约（V2-2.1 冻结镜像）校验测试：extra=forbid、key 必存在、null 保真。"""

import copy

import pytest
from pydantic import ValidationError

from models.context import IncidentContext


def test_fixture_parses_and_keeps_contract_version(context_payload):
    context = IncidentContext.model_validate(context_payload)
    assert context.context_version == "v2-2.1"
    assert context.incident is not None
    assert context.incident.incident_type == "INVENTORY_MISMATCH"


def test_null_section_stays_none_not_defaulted(context_payload):
    """未计划的数据源为 null：必须保持 None，不能被默认成空对象/0。"""
    context = IncidentContext.model_validate(context_payload)
    assert context.runtime is None
    assert context.queue is None
    assert context.consumer_health is None
    assert context.metrics is not None  # 已计划的段存在


def test_counter_value_is_preserved_while_presence_is_false(context_payload):
    """counter_presence=false 时数值仍按契约原样保留（是否可引用交给 Prompt 规则）。"""
    context = IncidentContext.model_validate(context_payload)
    assert context.metrics is not None
    assert context.metrics.counter_presence["consume_error"] is False
    assert context.metrics.counters["consume_error"] == 0.0


def test_present_false_keeps_value_null(context_payload):
    """absent ≠ 0：present=false 时 value 必须是 null。"""
    payload = copy.deepcopy(context_payload)
    payload["redis"]["voucher_stock"] = {
        "key": "seckill:stock:7001",
        "present": False,
        "value": None,
    }
    context = IncidentContext.model_validate(payload)
    assert context.redis is not None
    assert context.redis.voucher_stock is not None
    assert context.redis.voucher_stock.present is False
    assert context.redis.voucher_stock.value is None


def test_missing_field_is_rejected_not_silently_nulled(context_payload):
    """字段缺失必须校验失败，不能静默变成 null。"""
    payload = copy.deepcopy(context_payload)
    del payload["incident"]["status"]
    with pytest.raises(ValidationError) as exc:
        IncidentContext.model_validate(payload)
    assert "status" in str(exc.value)


def test_missing_top_level_field_is_rejected(context_payload):
    payload = copy.deepcopy(context_payload)
    del payload["context_quality"]
    with pytest.raises(ValidationError):
        IncidentContext.model_validate(payload)


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


def test_datetime_semantics(context_payload):
    """built_at/observed_at 为 UTC；incident 时间保持 naive（无时区原值）。"""
    context = IncidentContext.model_validate(context_payload)
    assert context.built_at.tzinfo is not None
    assert context.built_at.utcoffset().total_seconds() == 0
    assert context.incident is not None
    assert context.incident.first_detected_at.tzinfo is None
    assert context.incident.observed_at.tzinfo is not None


def test_derived_missing_redis_fixture(missing_redis_payload):
    """派生的"Redis 不可用"样本：段为 null 且质量信息如实反映。"""
    context = IncidentContext.model_validate(missing_redis_payload)
    assert context.redis is None
    assert context.context_quality.complete is False
    assert "redis" in context.context_quality.unavailable_sources
    assert context.context_quality.errors[0].error_type == "REDIS_UNAVAILABLE"
