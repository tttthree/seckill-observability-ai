package com.hmdp.controller;

import com.hmdp.entity.Incident;
import com.hmdp.enums.IncidentStatus;
import com.hmdp.enums.IncidentType;
import com.hmdp.service.IncidentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 故障事件查询接口。
 * <p>
 * 与 /admin 其他只读运维接口一致：GET 由 AdminAuthInterceptor 放行、LoginInterceptor 已排除，
 * 因此不需要用户登录态。故障事件只由系统内部检测逻辑产生，这里不提供创建接口。
 */
@Slf4j
@RestController
@RequestMapping("/admin/incidents")
public class IncidentController {

    @Resource
    private IncidentService incidentService;

    /**
     * 查询故障事件列表，按最近检测时间倒序。
     * GET /admin/incidents?status=OPEN&type=INVENTORY_MISMATCH&voucherId=1&limit=50
     */
    @GetMapping
    public Map<String, Object> listIncidents(
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) IncidentType type,
            @RequestParam(required = false) Long voucherId,
            @RequestParam(required = false) Integer limit) {

        List<Incident> incidents = incidentService.listIncidents(status, type, voucherId, limit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", incidents.size());
        result.put("items", incidents);
        return result;
    }

    /**
     * 查询单个故障事件详情。
     * GET /admin/incidents/{incidentId}
     */
    @GetMapping("/{incidentId}")
    public Map<String, Object> incidentDetail(@PathVariable Long incidentId) {
        Incident incident = incidentService.getIncident(incidentId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", incident != null);
        if (incident != null) {
            result.put("incident", incident);
        }
        return result;
    }
}
