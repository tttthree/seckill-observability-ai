package com.hmdp.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 故障事件状态。
 * <p>
 * 同一 (type, businessKey) 同一时刻最多一条 OPEN；恢复后置为 RESOLVED 并释放 open_key，
 * 使后续复发能够生成新的故障事件而不覆盖历史。
 */
@Getter
public enum IncidentStatus {

    OPEN("OPEN"),
    RESOLVED("RESOLVED");

    @EnumValue
    private final String code;

    IncidentStatus(String code) {
        this.code = code;
    }
}
