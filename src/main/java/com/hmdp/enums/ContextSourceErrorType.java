package com.hmdp.enums;

import lombok.Getter;

/**
 * Context 数据源读取失败的分类。
 * <p>
 * 只用于标记"哪个数据源、以什么方式失败"，不携带原始异常信息：
 * API 只输出 source / errorType / 通用 message，原始异常写入服务日志。
 */
@Getter
public enum ContextSourceErrorType {

    REDIS_UNAVAILABLE("redis source unavailable"),
    REDIS_ERROR("redis read failed"),
    DATABASE_UNAVAILABLE("database source unavailable"),
    DATABASE_ERROR("database read failed"),
    HEALTH_ERROR("consumer health source unavailable"),
    RUNTIME_ERROR("runtime source unavailable"),
    PARSE_ERROR("stored evidence snapshot could not be parsed");

    /** 安全的通用描述，禁止放入异常详情 */
    private final String safeMessage;

    ContextSourceErrorType(String safeMessage) {
        this.safeMessage = safeMessage;
    }
}
