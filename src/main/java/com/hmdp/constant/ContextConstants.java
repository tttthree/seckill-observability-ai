package com.hmdp.constant;

import java.util.List;

/**
 * Incident Context Builder（V2-2）采集上限与契约常量。
 * <p>
 * 放在新文件中，避免修改 V2-1 已冻结的 SeckillProperties / IncidentConstants。
 */
public class ContextConstants {

    /** Context 契约版本，Java→Python 输入契约变更时必须递增 */
    public static final String CONTEXT_VERSION = "v2-2.1";

    // ==================== 数据源名称（与 JSON 段名一致） ====================

    public static final String SOURCE_INCIDENT = "incident";
    public static final String SOURCE_METRICS = "metrics";
    public static final String SOURCE_REDIS = "redis";
    public static final String SOURCE_DATABASE = "database";
    public static final String SOURCE_QUEUE = "queue";
    public static final String SOURCE_CONSUMER_HEALTH = "consumer_health";
    public static final String SOURCE_RUNTIME = "runtime";
    /** 当前版本无结构化日志源，固定标记为未实现（不影响 complete） */
    public static final String SOURCE_LOGS = "logs";

    // ==================== 采集上限（Bounded collection） ====================

    /** database.recent_orders 最多返回条数 */
    public static final int RECENT_ORDERS_LIMIT = 20;
    /** incident.recent_previous_incidents 最多返回条数（不含当前 Incident） */
    public static final int RECENT_PREVIOUS_INCIDENTS_LIMIT = 5;
    /** 死信流从最新端读取的最大条数 */
    public static final int DEAD_LETTER_SCAN_LIMIT = 50;

    // ==================== snapshot 字段白名单（按 IncidentType） ====================
    // 只投影白名单字段，绝不把原始 JSON 暴露给下游；user_id 在 DEAD_LETTER 白名单中被刻意排除。

    public static final List<String> SNAPSHOT_FIELDS_INVENTORY_MISMATCH = List.of(
            "voucher_id", "redis_stock", "db_stock", "deviation", "detected_at");

    public static final List<String> SNAPSHOT_FIELDS_DEAD_LETTER = List.of(
            "message_id", "voucher_id", "order_id", "failure_reason", "max_retry", "detected_at");

    public static final List<String> SNAPSHOT_FIELDS_CONSUMER_UNHEALTHY = List.of(
            "health_status", "consumer_status", "reason",
            "heartbeat_age_ms", "success_heartbeat_age_ms", "pending_count", "checked_at");

    // ==================== 契约说明（写入 context_quality.notes） ====================

    public static final String NOTE_TIME_SEMANTICS =
            "built_at 与各段 observed_at 为 UTC Instant；incident 段时间字段原样取自数据库 LocalDateTime，"
                    + "历史存储不含时区信息，未附加任何 offset";

    public static final String NOTE_EVIDENCE_VS_STATE =
            "incident 段（含 detected_snapshot）是检测时刻证据；其余各段是构建时刻读到的当前状态，两者不可混用";

    public static final String NOTE_SNAPSHOT_SCOPE =
            "detected_snapshot 仅保留最近一次检测证据（scope=LATEST_DETECTION），更早 occurrence 的证据已被聚合覆盖";

    public static final String NOTE_METRICS_CONVENTION =
            "metrics.counters 为 Redis 计数器原始值；counter_presence=false 表示该计数器 key 不存在，"
                    + "按项目既有约定以 0 参与计算，可能代表无事件或已过期(TTL 7200s)";

    public static final String NOTE_PRIVACY =
            "detected_snapshot 与 recent_orders 均按白名单投影，不含 user_id 等用户标识";

    public static final String NOTE_LOGS =
            "logs 在当前版本不可用（无结构化日志源），列为 not_implemented_sources，不影响 complete";

    public static final String NOTE_GROUP_LAG_NOT_COLLECTED =
            "queue.consumer_group 不包含 lag / entries_read：当前客户端栈无法稳定获取"
                    + "（spring-data-redis 2.7.18 未暴露该字段，原始命令在 Lettuce 下无法解码含整数的嵌套回复），"
                    + "契约中刻意不提供，避免永久为 null 的字段进入下游";

    private ContextConstants() {
    }
}
