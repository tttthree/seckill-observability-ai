package com.hmdp.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 故障事件级别。rank 仅用于聚合时"只升不降"的比较。
 */
@Getter
public enum IncidentSeverity {

    LOW("LOW", 1),
    MEDIUM("MEDIUM", 2),
    HIGH("HIGH", 3),
    CRITICAL("CRITICAL", 4);

    @EnumValue
    private final String code;

    private final int rank;

    IncidentSeverity(String code, int rank) {
        this.code = code;
        this.rank = rank;
    }
}
