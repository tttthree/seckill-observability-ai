package com.hmdp.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 故障事件来源组件，用于区分"谁发现的故障"。
 */
@Getter
public enum IncidentSource {

    /** VoucherOrderServiceImpl.reconcile() 两阶段库存对账 */
    RECONCILE("RECONCILE"),

    /** Redis Stream 消费链路（Pending 重试超限 → 死信） */
    STREAM_CONSUMER("STREAM_CONSUMER"),

    /** ConsumerHealthIndicator 健康判定 */
    HEALTH_INDICATOR("HEALTH_INDICATOR");

    @EnumValue
    private final String code;

    IncidentSource(String code) {
        this.code = code;
    }
}
