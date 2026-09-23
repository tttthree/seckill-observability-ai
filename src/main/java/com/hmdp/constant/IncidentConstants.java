package com.hmdp.constant;

/**
 * 故障事件（Incident）相关常量。
 */
public class IncidentConstants {

    /** 券维度故障的业务键前缀：voucher:{voucherId} */
    public static final String BUSINESS_KEY_VOUCHER_PREFIX = "voucher:";

    /** open_key 组成分隔符：{incidentType}:{businessKey} */
    public static final String OPEN_KEY_SEPARATOR = ":";

    /** 消费者组维度故障的业务键：stream.orders:g1 */
    public static final String BUSINESS_KEY_CONSUMER_GROUP =
            RedisConstants.STREAM_ORDERS_KEY + OPEN_KEY_SEPARATOR + RedisConstants.STREAM_ORDERS_GROUP;

    /** open_key 列长度上限（兼容 utf8mb4 唯一索引 191 字节/字符约定） */
    public static final int OPEN_KEY_MAX_LENGTH = 191;

    /** title 列长度上限 */
    public static final int TITLE_MAX_LENGTH = 255;

    /** description 列长度上限 */
    public static final int DESCRIPTION_MAX_LENGTH = 1000;

    /**
     * 死信恢复判定单次扫描条数上限（扫描安全边界，不是故障判定阈值）。
     * 达到上限时不判定"已无死信"，避免误报恢复。
     */
    public static final int DEAD_LETTER_SCAN_LIMIT = 2000;
}
