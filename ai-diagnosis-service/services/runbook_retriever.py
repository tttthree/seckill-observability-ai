"""Runbook 检索（V2-5）：确定性、纯本地、无 embedding / 无向量库 / 无分词依赖。

冻结策略：
- `incident_type` **硬过滤**：只在该故障类型的知识条目内排序；
- 同类型内打分：`signal_hit * 2 + keyword_hit * 1`（keyword 最多计 3 个）；
- 排序：score 降序、id 升序（稳定）；
- 取 Top-2（常量，不做配置项）；
- 无阈值：类型匹配即候选，是否注入由 prompt 层按长度上限决定；
- 阈值语义严格复用项目既有定义：pending `> 1000`、心跳 `> 30000ms`、成功心跳 `> 60000ms`。
"""

import logging
from dataclasses import dataclass
from typing import Iterable, Tuple

from models.context import IncidentContext
from models.runbook import KNOWN_COUNTERS, RetrievedRunbook, Runbook
from services.runbook_loader import RunbookStore

logger = logging.getLogger(__name__)

TOP_K = 2
SIGNAL_WEIGHT = 2
KEYWORD_WEIGHT = 1
MAX_KEYWORD_HITS = 3

# 与 Java ConsumerHealthIndicator 完全一致的阈值（严格大于）
PENDING_HIGH_THRESHOLD = 1000
HEARTBEAT_AGE_HIGH_MS = 30_000
SUCCESS_HEARTBEAT_AGE_HIGH_MS = 60_000

# 关键词匹配的自由文本上限（防止把整份 Context 当 haystack）
QUERY_TEXT_MAX_CHARS = 2000


@dataclass(frozen=True)
class RetrievalResult:
    runbooks: Tuple[RetrievedRunbook, ...]
    candidates: int


def _positive_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0


def derive_signals(context: IncidentContext) -> frozenset:
    """从 EventContext 已存在的字段派生闭集信号（不新增数据源、不做推断）。"""
    signals = set()

    incident = context.incident
    snapshot = incident.detected_snapshot if incident is not None else None
    if isinstance(snapshot, dict):
        redis_stock = snapshot.get("redis_stock")
        db_stock = snapshot.get("db_stock")
        deviation = snapshot.get("deviation")
        if _positive_number(deviation):
            signals.add("snapshot:deviation_positive")
        if isinstance(redis_stock, (int, float)) and isinstance(db_stock, (int, float)):
            if redis_stock < db_stock:
                signals.add("snapshot:redis_lt_db")
            elif redis_stock > db_stock:
                signals.add("snapshot:redis_gt_db")

    metrics = context.metrics
    if metrics is not None:
        presence = metrics.counter_presence or {}
        counters = metrics.counters or {}
        for name, present in presence.items():
            if present is True and name in KNOWN_COUNTERS:
                signals.add(f"counter_present:{name}")
        for name, value in counters.items():
            # 与 evidence 回校验同一语义：presence 不严格为 true 的计数器不得参与检索
            if name in KNOWN_COUNTERS and presence.get(name) is True and _positive_number(value):
                signals.add(f"counter_positive:{name}")

    redis = context.redis
    if redis is not None:
        if redis.voucher_stock is not None and redis.voucher_stock.present is False:
            signals.add("redis:stock_absent")
        if redis.dirty_vouchers is not None and redis.dirty_vouchers.present is True:
            signals.add("redis:dirty_vouchers_present")
        if redis.reconcile_mismatch_marker is not None and redis.reconcile_mismatch_marker.present is True:
            signals.add("redis:mismatch_marker_present")

    queue = context.queue
    if queue is not None:
        entries = queue.dead_letter_entries_for_voucher or []
        stream = queue.dead_letter_stream
        if entries or (stream is not None and _positive_number(stream.length)):
            signals.add("dead_letter:present")
        for entry in entries:
            reason = (entry.failure_reason or "").strip()
            if reason:
                signals.add(f"dead_letter:reason:{reason}")

    health = context.consumer_health
    if health is not None:
        if health.consumer_alive is False:
            signals.add("consumer:alive:false")
        if health.status == "DOWN":
            signals.add("consumer:status:DOWN")
        elif health.status == "UP":
            signals.add("consumer:status:UP")
        if health.consumer_status == "DEGRADED":
            signals.add("consumer:consumer_status:DEGRADED")
        elif health.consumer_status == "HEALTHY":
            signals.add("consumer:consumer_status:HEALTHY")
        if health.pending_count is not None and health.pending_count > PENDING_HIGH_THRESHOLD:
            signals.add("consumer:pending_high")
        if health.heartbeat_age_ms is not None and health.heartbeat_age_ms > HEARTBEAT_AGE_HIGH_MS:
            signals.add("consumer:heartbeat_age_high")
        if (health.success_heartbeat_age_ms is not None
                and health.success_heartbeat_age_ms > SUCCESS_HEARTBEAT_AGE_HIGH_MS):
            signals.add("consumer:success_heartbeat_age_high")

    return frozenset(signals)


def build_query_text(context: IncidentContext) -> str:
    """关键词匹配用的自由文本（全部来自 Context 已有字段）。"""
    parts = []
    incident = context.incident
    if incident is not None:
        parts.extend([incident.title or "", incident.description or ""])
        snapshot = incident.detected_snapshot
        if isinstance(snapshot, dict):
            for key in ("reason", "failure_reason"):
                value = snapshot.get(key)
                if isinstance(value, str):
                    parts.append(value)
    if context.consumer_health is not None and context.consumer_health.reason:
        parts.append(context.consumer_health.reason)
    return "\n".join(parts)[:QUERY_TEXT_MAX_CHARS].lower()


def _score(runbook: Runbook, signals: Iterable, query_text: str) -> int:
    signal_hits = sum(1 for signal in (runbook.match_signals or []) if signal in signals)
    keyword_hits = sum(1 for keyword in runbook.keywords if keyword.lower() in query_text)
    return signal_hits * SIGNAL_WEIGHT + min(MAX_KEYWORD_HITS, keyword_hits) * KEYWORD_WEIGHT


class RunbookRetriever:
    """基于已加载 KB 的确定性检索器（构造后无 IO）。"""

    def __init__(self, store: RunbookStore) -> None:
        self._store = store

    @property
    def ready(self) -> bool:
        return bool(self._store.ready and self._store.runbooks)

    def retrieve(self, context: IncidentContext) -> RetrievalResult:
        if not self.ready:
            return RetrievalResult((), 0)

        incident = context.incident
        incident_type = incident.incident_type if incident is not None else None
        if not incident_type:
            return RetrievalResult((), 0)

        signals = derive_signals(context)
        query_text = build_query_text(context)

        candidates = [
            (_score(runbook, signals, query_text), runbook)
            for runbook in self._store.runbooks
            if incident_type in runbook.incident_types
        ]
        candidates.sort(key=lambda item: (-item[0], item[1].id))

        selected = tuple(
            RetrievedRunbook(
                id=runbook.id,
                title=runbook.title,
                score=score,
                summary=runbook.summary,
                checks=list(runbook.checks),
                do_not=list(runbook.do_not),
            )
            for score, runbook in candidates[:TOP_K]
        )
        return RetrievalResult(selected, len(candidates))
