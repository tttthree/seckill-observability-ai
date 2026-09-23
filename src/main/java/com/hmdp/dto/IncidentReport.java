package com.hmdp.dto;

import com.hmdp.enums.IncidentSeverity;
import com.hmdp.enums.IncidentSource;
import com.hmdp.enums.IncidentType;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * 一次故障检测的上报内容。
 * <p>
 * 由检测方（对账任务、死信路由、健康检测器）构造，IncidentService 负责聚合与持久化。
 */
@Data
@Accessors(chain = true)
public class IncidentReport {

    private IncidentType incidentType;

    private IncidentSource source;

    private IncidentSeverity severity;

    /** 同一故障的聚合键，例如 voucher:123 */
    private String businessKey;

    /** 关联券 id，非券维度故障为 null */
    private Long relatedVoucherId;

    private String title;

    private String description;

    /** 结构化证据快照，序列化后写入 snapshot 列 */
    private Map<String, Object> evidence;
}
