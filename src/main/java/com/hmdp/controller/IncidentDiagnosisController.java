package com.hmdp.controller;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.hmdp.config.AiDiagnosisConfig;
import com.hmdp.dto.context.IncidentContext;
import com.hmdp.service.AiDiagnosisClient;
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
 * V2-4 单事件 AI 诊断接口。
 *
 * <p>
 * 鉴权沿用 {@code /admin/incidents/**} 规则（GET 也需要 {@code X-Admin-Token}，由 AdminAuthInterceptor 完成，
 * 本类零鉴权代码）。诊断只读：复用 V2-2 {@link IncidentContextBuilder} 采集真实证据，
 * 再交给独立 Python 服务做结构化诊断；AI 侧任何失败都由客户端降级为
 * {@code diagnosis_status=UNAVAILABLE} 并附明确 {@code error_code}，**不伪造成 DIAGNOSED**，
 * 也不影响秒杀主链路。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/admin/incidents")
public class IncidentDiagnosisController {

    @Resource
    private IncidentContextBuilder incidentContextBuilder;

    @Resource
    private AiDiagnosisClient aiDiagnosisClient;

    /**
     * GET /admin/incidents/{incidentId}/diagnosis
     *
     * <ul>
     *   <li>Incident 不存在：404 + INCIDENT_NOT_FOUND，**不调用 Python**</li>
     *   <li>命中 Sentinel 限流：200 + AI_RATE_LIMITED（attempts=0），**不调用 Python**</li>
     *   <li>正常：200 + 诊断结果（Python 原样透传 / Java 本地降级）</li>
     * </ul>
     */
    @GetMapping("/{incidentId}/diagnosis")
    public ResponseEntity<Object> diagnosis(@PathVariable Long incidentId) {
        IncidentContext context = incidentContextBuilder.build(incidentId);
        if (context == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "INCIDENT_NOT_FOUND");
            body.put("incident_id", incidentId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }

        Entry entry = null;
        try {
            entry = SphU.entry(AiDiagnosisConfig.RESOURCE_INCIDENT_DIAGNOSIS);
        } catch (BlockException e) {
            log.warn("AI 诊断请求被限流（QPS 保护），本地降级 incidentId={}", incidentId);
            return ResponseEntity.ok(aiDiagnosisClient.rateLimited(context));
        }

        try {
            return ResponseEntity.ok(aiDiagnosisClient.diagnose(context));
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }
}
