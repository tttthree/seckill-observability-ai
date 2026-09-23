package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hmdp.enums.IncidentSeverity;
import com.hmdp.enums.IncidentSource;
import com.hmdp.enums.IncidentStatus;
import com.hmdp.enums.IncidentType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 统一故障事件：把对账偏差、死信、消费者不健康等异常抽象为可持久化、可查询的事件。
 * </p>
 *
 * <p>
 * 主键使用数据库自增：故障发生时 Redis 本身可能不可用，故障事件的落库不能依赖 Redis 生成 ID。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_incident")
public class Incident implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 故障类型 */
    private IncidentType incidentType;

    /** 故障级别 */
    private IncidentSeverity severity;

    /** 发现故障的组件 */
    private IncidentSource source;

    /** 状态：OPEN / RESOLVED */
    private IncidentStatus status;

    /** 聚合键，例如 voucher:123 */
    private String businessKey;

    /**
     * 去重键：OPEN 时为 incidentType:businessKey，RESOLVED 时为 null。
     * 唯一索引 uk_incident_open 保证同一故障同时只有一条 OPEN 事件。
     */
    private String openKey;

    private String title;

    /** 最近一次检测证据的可读描述 */
    private String description;

    /** 关联券 id（券维度故障） */
    private Long relatedVoucherId;

    /** 累计检测次数：持续异常只递增，不新增行 */
    private Integer occurrenceCount;

    /** 首次发现时间 */
    private LocalDateTime firstDetectedAt;

    /** 最近一次检测时间 */
    private LocalDateTime lastDetectedAt;

    /** 恢复时间 */
    private LocalDateTime resolvedAt;

    /** 最近一次检测的结构化证据（JSON） */
    private String snapshot;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
