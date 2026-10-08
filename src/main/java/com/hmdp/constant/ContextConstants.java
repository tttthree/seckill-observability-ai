package com.hmdp.constant;

import java.util.List;

/**
 * IncidentContext 采集上限与契约常量。
 * <p>
 * 只登记当前真实存在的采集能力与契约字段；已无实现的能力（结构化日志、消费者组 lag）
 * 不在此定义，也不在下游契约中以 null / "未实现" 出现。
 */
public class ContextConstants {

    /** Context 契约版本，Java→Python 输入契约变更时必须递增 */
    public static final String CONTEXT_VERSION = "v3.0";

    // ==================== 数据源名称（与 JSON 段名一致） ====================

    public static final String SOURCE_INCIDENT = "incident";
    public static final String SOURCE_REDIS = "redis";
    public static final String SOURCE_DATABASE = "database";
    public static final String SOURCE_QUEUE = "queue";
    public static final String SOURCE_CONSUMER_HEALTH = "consumer_health";

    // ==================== 采集上限（Bounded collection） ====================

    /** database.recent_orders 最多返回条数（不下发为字段） */
    public static final int RECENT_ORDERS_LIMIT = 5;
    /** 死信流从最新端读取的最大条数（达到上限只记日志，不进契约） */
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

    private ContextConstants() {
    }
}
