"""IncidentContext v3.0 输入契约镜像（冻结）。

冻结规则：
- `extra="forbid"`：契约升级必须通过 context_version 升版，不做隐式前向兼容；
- 契约中**所有 key 都视为必存在**（Java 侧以 @JsonInclude(ALWAYS) 序列化，null 也会写出），
  因此字段一律声明为 required；可空字段写成 `Optional[...]` 而**不给默认值**——
  这样"字段缺失"会直接校验失败，不会被静默转成 null；
- 段本身可为 null（未计划采集 / 读取失败），用 `Optional[Section]` 表达；
- **只建模当前真实能采的字段**：结构化日志、消费者组 lag / entries_read、
  运行指标(metrics)、JVM(runtime) 在能力落地前不出现在契约里，也不以 null 占位；
- 时间语义：`built_at` 为 UTC Instant（带时区）；
  `incident.*_detected_at` / `resolved_at` / `recent_orders.create_time` 为数据库中的
  无时区 LocalDateTime（naive，原样保留）；`detected_snapshot.detected_at` 为 epoch millis。
"""

from datetime import datetime
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, ConfigDict


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


# ==================== incident ====================


class IncidentEvidence(_StrictModel):
    incident_id: int
    incident_type: str
    severity: str
    status: str
    related_voucher_id: Optional[int]
    occurrence_count: int
    first_detected_at: datetime
    last_detected_at: datetime
    resolved_at: Optional[datetime]
    title: str
    description: Optional[str]
    detected_snapshot: Optional[Dict[str, Any]]


# ==================== redis ====================


class StockState(_StrictModel):
    """库存 key 的真实读取结果：present=false 时 value 必须为 null（不得写成 0）。"""

    present: bool
    value: Optional[int]


class RedisEvidence(_StrictModel):
    stock: Optional[StockState]
    # Set 不存在时语义就是 0（正常空集合）；读取失败由 redis=null + unavailable_sources 表达
    ordered_user_count: int
    dirty: Optional[bool]
    mismatch_pending: Optional[bool]


# ==================== database ====================


class RecentOrder(_StrictModel):
    order_id: Optional[int]
    create_time: Optional[datetime]


class DatabaseEvidence(_StrictModel):
    voucher_exists: bool
    stock: Optional[int]
    order_count: Optional[int]
    recent_orders: Optional[List[RecentOrder]]


# ==================== queue ====================


class DeadLetterEntry(_StrictModel):
    """message_id 为原主 Stream 的消息 id。"""

    message_id: Optional[str]
    order_id: Optional[int]
    failure_reason: Optional[str]


class QueueEvidence(_StrictModel):
    pending_count: Optional[int]
    # 刻意不含整个死信流的 XLEN：global 死信数 > 0 不代表当前券有死信（维度歧义）。
    # 判断"该券是否仍有死信"只看 dead_letters 是否非空。
    dead_letters: Optional[List[DeadLetterEntry]]


# ==================== consumer health ====================


class ConsumerHealthEvidence(_StrictModel):
    """status 由 Actuator UP/DOWN 与内部 consumer_status 投影：HEALTHY / DEGRADED / DOWN。"""

    status: str
    heartbeat_age_ms: Optional[int]
    success_heartbeat_age_ms: Optional[int]
    pending_count: Optional[int]
    reason: Optional[str]


# ==================== root ====================


class IncidentContext(_StrictModel):
    context_version: str
    built_at: datetime
    incident: Optional[IncidentEvidence]
    redis: Optional[RedisEvidence]
    database: Optional[DatabaseEvidence]
    queue: Optional[QueueEvidence]
    consumer_health: Optional[ConsumerHealthEvidence]
    unavailable_sources: List[str]


class DiagnosisRequest(_StrictModel):
    """POST /api/v1/diagnosis 请求体。"""

    incident_context: IncidentContext
