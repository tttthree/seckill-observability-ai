package com.hmdp.constant;

/**
 * 所有 Redis key 前缀
 */
public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    //空值缓存过期时间
    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final Long CACHE_TYPE_TTL = 1L;
    public static final String CACHE_TYPE_KEY = "cache:shoptype:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String STREAM_ORDERS_KEY = "stream.orders";
    public static final String STREAM_ORDERS_DEAD_KEY = "stream.orders.dead";
    public static final String STREAM_ORDERS_GROUP = "g1";
    public static final String SECKILL_VOUCHER_DIRTY_KEY = "seckill:voucher:dirty";
    public static final String STREAM_RETRY_KEY = "stream:retry:";
}
