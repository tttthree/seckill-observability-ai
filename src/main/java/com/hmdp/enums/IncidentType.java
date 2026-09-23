package com.hmdp.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 统一故障事件类型。
 * <p>
 * 只登记当前链路中已有真实检测依据的故障，不为凑类型新增虚构检测逻辑。
 */
@Getter
public enum IncidentType {

    /** Redis 库存与 MySQL 库存经两阶段对账确认的持续不一致 */
    INVENTORY_MISMATCH("INVENTORY_MISMATCH", "库存不一致"),

    /** 重试超限后被原子补偿并写入死信队列的订单消息 */
    DEAD_LETTER("DEAD_LETTER", "死信消息"),

    /** ConsumerHealthIndicator 判定为不可用或消费停滞 */
    CONSUMER_UNHEALTHY("CONSUMER_UNHEALTHY", "消费者不健康");

    /** 落库值，同时用于 open_key 前缀 */
    @EnumValue
    private final String code;

    /** 中文标签，仅用于生成可读标题 */
    private final String label;

    IncidentType(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
