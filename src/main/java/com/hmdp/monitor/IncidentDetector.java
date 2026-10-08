package com.hmdp.monitor;

import com.hmdp.config.SeckillProperties;
import com.hmdp.constant.IncidentConstants;
import com.hmdp.dto.IncidentReport;
import com.hmdp.enums.IncidentSeverity;
import com.hmdp.enums.IncidentSource;
import com.hmdp.enums.IncidentType;
import com.hmdp.service.IncidentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 故障事件状态检测器：只负责读取既有检测结果并驱动 Incident 的 OPEN / UPDATE / RESOLVE。
 *
 * <p>
 * 本类<b>不做任何阈值判断</b>：消费者心跳超时(30s)、Pending 堆积(PENDING_ALERT_THRESHOLD)、
 * 消费停滞(DEGRADED 判定)全部由现有 {@link ConsumerHealthIndicator#health()} 完成，
 * 这里只读取其 status 与 consumer_status/reason 明细，把"不健康"这一既有判定映射为故障事件。
 * </p>
 *
 * <p>
 * 本类<b>不处理 DEAD_LETTER</b>：Resume-Lite 不实现自动重放 / 补偿 / 恢复闭环，
 * 死信事件进入后保持 OPEN，作为"待人工核查"的故障事件。
 * </p>
 */
@Slf4j
@Component
public class IncidentDetector {

    @Resource
    private IncidentService incidentService;

    @Resource
    private ConsumerHealthIndicator consumerHealthIndicator;

    @Resource
    private SeckillProperties seckillProperties;

    @Scheduled(fixedDelayString = "#{@seckillProperties.incident.detectorIntervalMs}")
    public void detect() {
        if (!seckillProperties.getIncident().isEnabled()) {
            return;
        }

        try {
            detectConsumerHealth();
        } catch (Exception e) {
            log.warn("消费者健康故障状态同步失败", e);
        }
    }

    // ==================== 消费者健康：状态读取 + OPEN/RESOLVE ====================

    private void detectConsumerHealth() {
        Health health = consumerHealthIndicator.health();
        Map<String, Object> details = health.getDetails();

        boolean up = Status.UP.equals(health.getStatus());
        // consumer_status 由 ConsumerHealthIndicator 计算（HEALTHY / DEGRADED），此处只读取
        String consumerStatus = up
                ? String.valueOf(details.getOrDefault("consumer_status", "UNKNOWN"))
                : "DOWN";

        if (up && "HEALTHY".equals(consumerStatus)) {
            incidentService.resolve(IncidentType.CONSUMER_UNHEALTHY,
                    IncidentConstants.BUSINESS_KEY_CONSUMER_GROUP);
            return;
        }

        String reason = String.valueOf(details.getOrDefault("reason", "未提供原因"));
        IncidentSeverity severity = up ? IncidentSeverity.MEDIUM : IncidentSeverity.CRITICAL;
        String title = up ? "秒杀消费者线程消费停滞" : "秒杀消费者线程不可用";
        String description = up
                ? "消费者健康检查返回 UP 但状态为 DEGRADED：" + reason
                : "消费者健康检查返回 DOWN：" + reason;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("health_status", health.getStatus().getCode());
        evidence.put("consumer_status", consumerStatus);
        evidence.put("reason", reason);
        evidence.put("heartbeat_age_ms", details.getOrDefault("heartbeat_age_ms", -1L));
        evidence.put("success_heartbeat_age_ms", details.getOrDefault("success_heartbeat_age_ms", -1L));
        evidence.put("pending_count", details.getOrDefault("pending_count", 0));
        evidence.put("checked_at", System.currentTimeMillis());

        incidentService.report(new IncidentReport()
                .setIncidentType(IncidentType.CONSUMER_UNHEALTHY)
                .setSource(IncidentSource.HEALTH_INDICATOR)
                .setSeverity(severity)
                .setBusinessKey(IncidentConstants.BUSINESS_KEY_CONSUMER_GROUP)
                .setTitle(title)
                .setDescription(description)
                .setEvidence(evidence));
    }
}
