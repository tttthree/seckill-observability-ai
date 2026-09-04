package com.hmdp.constant;

/**
 * 秒杀系统指标常量
 * @author zt
 * @version 1.0
 */
public class MetricsConstants {
    /**
     * 监控指标
     */

    //请求层

    // 请求总数
    public static final String M_TOTAL_REQUESTS = "seckill:metrics:total_requests";

    // 预占层（Redis Lua）

    // 预占成功
    public static final String M_RESERVE_SUCCESS = "seckill:metrics:reserve_success";
    // Redis/Lua异常
    public static final String M_RESERVE_ERROR = "seckill:metrics:reserve_error";
    // 重复请求
    public static final String M_DUPLICATE_REQUEST = "seckill:metrics:duplicate_request";

    // 成交层（DB事务）

    // 成交成功（原 order_success）
    public static final String M_COMMIT_SUCCESS = "seckill:metrics:commit_success";
    // DB写入失败
    public static final String M_COMMIT_ERROR = "seckill:metrics:commit_error";

    // 库存失败（分层原因）
    // Redis库存不足
    public static final String M_STOCK_FAIL_REDIS = "seckill:metrics:stock_fail_redis";
    // DB库存不足
    public static final String M_STOCK_FAIL_DB = "seckill:metrics:stock_fail_db";

    // 消费链路（异步）
    // 消费异常（Redis Stream 消费者线程异常）
    public static final String M_CONSUME_ERROR = "seckill:metrics:consume_error";

    // 对账修复（最终一致性）
    // 对账修复次数
    public static final String M_RECONCILE_FIX = "seckill:metrics:reconcile_fix";

    /** 计数器默认 TTL（秒），压测数据 2 小时后自动清理 */
    public static final long METRIC_TTL_SECONDS = 7200;

    /** 返回所有指标 key，用于 reset 接口批量清理 */
    public static String[] getAllMetricKeys() {
        return new String[] {
                M_TOTAL_REQUESTS,
                M_RESERVE_SUCCESS,
                M_RESERVE_ERROR,
                M_DUPLICATE_REQUEST,
                M_COMMIT_SUCCESS,
                M_COMMIT_ERROR,
                M_STOCK_FAIL_REDIS,
                M_STOCK_FAIL_DB,
                M_CONSUME_ERROR,
                M_RECONCILE_FIX
        };
    }
}
