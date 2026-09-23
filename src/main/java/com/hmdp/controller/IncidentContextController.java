package com.hmdp.controller;

import com.hmdp.dto.context.IncidentContext;
import com.hmdp.service.IncidentContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Incident Context 查询接口（V2-2）。
 *
 * <p>
 * 鉴权沿用 {@code /admin/incidents/**} 规则（GET 也需要 X-Admin-Token）。
 * 实时构建、不落库；非主证据数据源失败返回 HTTP 200 + partial context。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/admin/incidents")
public class IncidentContextController {

    @Resource
    private IncidentContextBuilder incidentContextBuilder;

    /**
     * GET /admin/incidents/{incidentId}/context
     *
     * <ul>
     *   <li>Incident 存在：200 + IncidentContext（可能 partial）</li>
     *   <li>Incident 不存在：404 + INCIDENT_NOT_FOUND</li>
     *   <li>主证据（Incident 行）读取异常：按既有运维查询语义抛出，由 WebExceptionAdvice 显式失败</li>
     * </ul>
     */
    @GetMapping("/{incidentId}/context")
    public ResponseEntity<Object> incidentContext(@PathVariable Long incidentId) {
        IncidentContext context = incidentContextBuilder.build(incidentId);
        if (context == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "INCIDENT_NOT_FOUND");
            body.put("incident_id", incidentId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        return ResponseEntity.ok(context);
    }
}
