"""Runbook 知识条目契约（V2-5）。

设计约束：
- 知识条目是**通用运维知识**，不是本次事故的事实；只有 summary / checks / do_not 会进入 prompt；
- `extra="forbid"`：知识文件写错字段会立刻被判为 invalid，而不是被静默忽略；
- `match_signals` 必须取自**闭集信号词表**（下方 SIGNAL_* 常量），拼写错误在加载期即暴露；
- 该模型的全部字段都来自人工评审的 YAML 文件，不来自任何运行时数据。
"""

import re
from datetime import date
from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator

# ==================== 允许声明的故障类型（与 Java IncidentType 一一对应） ====================

INCIDENT_TYPES = ("INVENTORY_MISMATCH", "DEAD_LETTER", "CONSUMER_UNHEALTHY")

# ==================== 闭集信号词表 ====================

# 计数器短名（与 Java MetricsConstants 的 10 个计数器一一对应）
KNOWN_COUNTERS = (
    "total_requests",
    "reserve_success",
    "reserve_error",
    "duplicate_request",
    "commit_success",
    "commit_error",
    "stock_fail_redis",
    "stock_fail_db",
    "consume_error",
    "reconcile_mismatch",
)

# 固定信号
FIXED_SIGNALS = frozenset(
    {
        "snapshot:deviation_positive",
        "snapshot:redis_lt_db",
        "snapshot:redis_gt_db",
        "redis:stock_absent",
        "redis:dirty_vouchers_present",
        "redis:mismatch_marker_present",
        "dead_letter:present",
        "consumer:alive:false",
        "consumer:status:DOWN",
        "consumer:status:UP",
        "consumer:consumer_status:DEGRADED",
        "consumer:consumer_status:HEALTHY",
        "consumer:pending_high",
        "consumer:heartbeat_age_high",
        "consumer:success_heartbeat_age_high",
    }
)

_REASON_SIGNAL_PREFIX = "dead_letter:reason:"
_COUNTER_SIGNAL_PREFIXES = ("counter_present:", "counter_positive:")

RUNBOOK_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{2,63}$")

MAX_KEYWORDS = 20
MAX_KEYWORD_CHARS = 40
MAX_TITLE_CHARS = 120
MAX_SUMMARY_CHARS = 200
MAX_CHECKS = 8
MAX_DO_NOT = 5
MAX_TEXT_CHARS = 200  # checks / do_not 单条上限
MAX_REFERENCES = 10


def is_known_signal(signal: str) -> bool:
    """信号是否属于闭集词表（含参数化形态）。"""
    if signal in FIXED_SIGNALS:
        return True
    if signal.startswith(_REASON_SIGNAL_PREFIX):
        return bool(signal[len(_REASON_SIGNAL_PREFIX) :].strip())
    for prefix in _COUNTER_SIGNAL_PREFIXES:
        if signal.startswith(prefix):
            return signal[len(prefix) :] in KNOWN_COUNTERS
    return False


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Runbook(_StrictModel):
    """一条人工评审过的运维知识条目。"""

    id: str
    title: str
    version: str
    updated_at: date
    incident_types: List[str]
    match_signals: Optional[List[str]] = None
    keywords: List[str] = Field(default_factory=list)
    summary: str
    checks: List[str]
    do_not: List[str] = Field(default_factory=list)
    references: List[str] = Field(default_factory=list)

    @field_validator("id")
    @classmethod
    def _check_id(cls, value: str) -> str:
        if not RUNBOOK_ID_PATTERN.match(value):
            raise ValueError("id 必须匹配 ^[a-z0-9][a-z0-9-]{2,63}$")
        return value

    @field_validator("title")
    @classmethod
    def _check_title(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("title 不能为空")
        if len(value) > MAX_TITLE_CHARS:
            raise ValueError(f"title 超过 {MAX_TITLE_CHARS} 字符")
        return value

    @field_validator("version", "updated_at")
    @classmethod
    def _check_version(cls, value: object) -> object:
        return value

    @field_validator("incident_types")
    @classmethod
    def _check_incident_types(cls, value: List[str]) -> List[str]:
        if not value:
            raise ValueError("incident_types 不能为空（Runbook 必须声明适用故障类型）")
        for item in value:
            if item not in INCIDENT_TYPES:
                raise ValueError(f"未知的 incident_type: {item}")
        return value

    @field_validator("match_signals")
    @classmethod
    def _check_signals(cls, value: Optional[List[str]]) -> Optional[List[str]]:
        if value is None:
            return None
        if len(value) > 10:
            raise ValueError("match_signals 最多 10 个")
        for signal in value:
            if not is_known_signal(signal):
                raise ValueError(f"未知信号（不在闭集词表内）: {signal}")
        return value

    @field_validator("keywords")
    @classmethod
    def _check_keywords(cls, value: List[str]) -> List[str]:
        if len(value) > MAX_KEYWORDS:
            raise ValueError(f"keywords 最多 {MAX_KEYWORDS} 个")
        for keyword in value:
            if not keyword.strip():
                raise ValueError("keyword 不能为空")
            if len(keyword) > MAX_KEYWORD_CHARS:
                raise ValueError(f"keyword 超过 {MAX_KEYWORD_CHARS} 字符")
        return value

    @field_validator("summary")
    @classmethod
    def _check_summary(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("summary 不能为空")
        if len(value) > MAX_SUMMARY_CHARS:
            raise ValueError(f"summary 超过 {MAX_SUMMARY_CHARS} 字符")
        return value

    @field_validator("checks")
    @classmethod
    def _check_checks(cls, value: List[str]) -> List[str]:
        if not value:
            raise ValueError("checks 至少 1 条")
        if len(value) > MAX_CHECKS:
            raise ValueError(f"checks 最多 {MAX_CHECKS} 条")
        for item in value:
            if not item.strip() or len(item) > MAX_TEXT_CHARS:
                raise ValueError(f"checks 每条必须非空且不超过 {MAX_TEXT_CHARS} 字符")
        return value

    @field_validator("do_not")
    @classmethod
    def _check_do_not(cls, value: List[str]) -> List[str]:
        if len(value) > MAX_DO_NOT:
            raise ValueError(f"do_not 最多 {MAX_DO_NOT} 条")
        for item in value:
            if not item.strip() or len(item) > MAX_TEXT_CHARS:
                raise ValueError(f"do_not 每条必须非空且不超过 {MAX_TEXT_CHARS} 字符")
        return value

    @field_validator("references")
    @classmethod
    def _check_references(cls, value: List[str]) -> List[str]:
        if len(value) > MAX_REFERENCES:
            raise ValueError(f"references 最多 {MAX_REFERENCES} 条")
        return value


class RetrievedRunbook(_StrictModel):
    """检索结果：只有会被注入 prompt 的字段（+ 分数，用于日志与审计）。"""

    id: str
    title: str
    score: int
    summary: str
    checks: List[str]
    do_not: List[str]
