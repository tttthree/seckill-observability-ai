"""V2-2.1 IncidentContext 输入契约镜像（冻结）。

冻结规则：
- `extra="forbid"`：契约升级必须通过 context_version 升版，不做隐式前向兼容；
- 契约中**所有 key 都视为必存在**（Java 侧以 @JsonInclude(ALWAYS) 序列化，null 也会写出），
  因此字段一律声明为 required；可空字段写成 `Optional[...]` 而**不给默认值**——
  这样"字段缺失"会直接校验失败，不会被静默转成 null；
- 段本身可为 null（未计划的数据源 / 整体不可用），用 `Optional[Section]` 表达；
- 时间语义：`built_at` / `observed_at` 为 UTC Instant（带时区）；
  `incident.*_detected_at` / `resolved_at` 为数据库中的无时区 LocalDateTime（naive，原样保留）；
  `detected_snapshot.detected_at` 为 epoch millis。
"""

from datetime import datetime
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, ConfigDict


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


# ==================== incident ====================


class PreviousIncident(_StrictModel):
    incident_id: int
    status: Optional[str]
    severity: Optional[str]
    occurrence_count: Optional[int]
    first_detected_at: Optional[datetime]
    last_detected_at: Optional[datetime]
    resolved_at: Optional[datetime]


class IncidentEvidence(_StrictModel):
    observed_at: datetime
    incident_id: int
    incident_type: str
    severity: str
    source: str
    status: str
    business_key: str
    related_voucher_id: Optional[int]
    occurrence_count: int
    first_detected_at: datetime
    last_detected_at: datetime
    resolved_at: Optional[datetime]
    title: str
    description: Optional[str]
    detected_snapshot: Optional[Dict[str, Any]]
    detected_snapshot_scope: Optional[str]
    recent_previous_incidents: Optional[List[PreviousIncident]]


# ==================== metrics ====================


class MetricsEvidence(_StrictModel):
    observed_at: datetime
    counters: Dict[str, Optional[float]]
    counter_presence: Dict[str, bool]


# ==================== redis ====================


class RedisScalarValue(_StrictModel):
    key: str
    present: bool
    value: Optional[int]


class RedisCardinality(_StrictModel):
    key: str
    present: bool
    cardinality: Optional[int]


class DirtyVouchers(_StrictModel):
    key: str
    present: bool
    member_count: Optional[int]
    contains_voucher: bool


class RedisEvidence(_StrictModel):
    observed_at: datetime
    voucher_stock: Optional[RedisScalarValue]
    voucher_ordered_users: Optional[RedisCardinality]
    dirty_vouchers: Optional[DirtyVouchers]
    reconcile_mismatch_marker: Optional[RedisScalarValue]


# ==================== database ====================


class SeckillVoucherRow(_StrictModel):
    voucher_id: int
    stock: Optional[int]
    begin_time: Optional[datetime]
    end_time: Optional[datetime]
    update_time: Optional[datetime]


class RecentOrder(_StrictModel):
    order_id: Optional[int]
    voucher_id: Optional[int]
    create_time: Optional[datetime]


class DatabaseEvidence(_StrictModel):
    observed_at: datetime
    seckill_voucher: Optional[SeckillVoucherRow]
    order_count_for_voucher: Optional[int]
    recent_orders: Optional[List[RecentOrder]]
    recent_orders_limit: int


# ==================== queue ====================


class StreamSummary(_StrictModel):
    key: str
    exists: bool
    length: Optional[int]
    first_entry_id: Optional[str]
    last_entry_id: Optional[str]
    last_generated_id: Optional[str]
    group_count: Optional[int]


class ConsumerGroupSummary(_StrictModel):
    name: str
    consumers_total: Optional[int]
    pending_total: Optional[int]
    last_delivered_id: Optional[str]


class DeadLetterStream(_StrictModel):
    key: str
    exists: bool
    length: Optional[int]
    scanned_from: Optional[str]
    scanned_limit: Optional[int]
    entries_found_for_voucher: Optional[int]


class DeadLetterEntry(_StrictModel):
    stream_entry_id: Optional[str]
    original_message_id: Optional[str]
    voucher_id: Optional[int]
    order_id: Optional[int]
    failure_reason: Optional[str]


class QueueEvidence(_StrictModel):
    observed_at: datetime
    main_stream: Optional[StreamSummary]
    consumer_group: Optional[ConsumerGroupSummary]
    dead_letter_stream: Optional[DeadLetterStream]
    dead_letter_entries_for_voucher: Optional[List[DeadLetterEntry]]


# ==================== consumer health / runtime ====================


class ConsumerHealthEvidence(_StrictModel):
    observed_at: datetime
    status: str
    consumer_status: Optional[str]
    consumer_alive: Optional[bool]
    heartbeat_age_ms: Optional[int]
    success_heartbeat_age_ms: Optional[int]
    pending_count: Optional[int]
    reason: Optional[str]


class RuntimeEvidence(_StrictModel):
    observed_at: datetime
    uptime_ms: Optional[int]
    heap_used_bytes: Optional[int]
    thread_count: Optional[int]
    available_processors: Optional[int]
    java_version: Optional[str]


# ==================== context quality ====================


class SourceError(_StrictModel):
    source: str
    error_type: str
    message: str


class Truncation(_StrictModel):
    source: str
    limit: Optional[int]
    returned: Optional[int]
    truncated: bool


class ContextQuality(_StrictModel):
    complete: bool
    planned_sources: List[str]
    available_sources: List[str]
    unavailable_sources: List[str]
    not_implemented_sources: List[str]
    errors: List[SourceError]
    truncations: List[Truncation]
    notes: List[str]


# ==================== root ====================


class IncidentContext(_StrictModel):
    context_version: str
    built_at: datetime
    incident: Optional[IncidentEvidence]
    metrics: Optional[MetricsEvidence]
    redis: Optional[RedisEvidence]
    database: Optional[DatabaseEvidence]
    queue: Optional[QueueEvidence]
    consumer_health: Optional[ConsumerHealthEvidence]
    runtime: Optional[RuntimeEvidence]
    context_quality: ContextQuality


class DiagnosisRequest(_StrictModel):
    """POST /api/v1/diagnosis 请求体。"""

    incident_context: IncidentContext
