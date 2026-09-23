package com.hmdp.service;

import com.hmdp.dto.IncidentReport;
import com.hmdp.entity.Incident;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.enums.IncidentStatus;
import com.hmdp.enums.IncidentType;

import java.util.List;

/**
 * <p>
 * 统一故障事件服务类
 * </p>
 *
 * <p>
 * 故障事件只由系统内部检测逻辑产生，不对外提供创建接口。
 * </p>
 */
public interface IncidentService extends IService<Incident> {

    /**
     * 上报一次故障检测。
     * <p>
     * 同一 (incidentType, businessKey) 已存在 OPEN 事件时只聚合更新
     * （occurrence_count + 1、刷新 last_detected_at 与 snapshot），不新增行。
     * 该方法内部吞掉所有异常：故障记录失败不得影响秒杀主链路。
     *
     * @return 新建的故障事件；聚合到既有事件、写入失败或参数不完整时返回 null
     */
    Incident report(IncidentReport report);

    /**
     * 将 (incidentType, businessKey) 的 OPEN 事件标记为 RESOLVED。
     * 幂等：不存在 OPEN 事件时返回 false。内部吞掉所有异常。
     *
     * @return 是否确实发生了状态变更
     */
    boolean resolve(IncidentType incidentType, String businessKey);

    /**
     * 列表查询，按 last_detected_at 倒序返回最新若干条。内部吞掉所有异常。
     *
     * @param status    可选，状态过滤
     * @param type      可选，类型过滤
     * @param voucherId 可选，关联券过滤
     * @param limit     可选，条数上限，按配置收敛
     */
    List<Incident> listIncidents(IncidentStatus status, IncidentType type, Long voucherId, Integer limit);

    /**
     * 按 id 查询详情。内部吞掉所有异常。
     */
    Incident getIncident(Long incidentId);

    /**
     * 查询某类型下全部 OPEN 事件，供恢复判定使用。
     */
    List<Incident> listOpenIncidents(IncidentType incidentType);
}
