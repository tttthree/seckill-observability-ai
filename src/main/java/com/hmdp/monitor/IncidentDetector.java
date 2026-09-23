package com.hmdp.monitor;

import com.hmdp.config.SeckillProperties;
import com.hmdp.constant.IncidentConstants;
import com.hmdp.constant.RedisConstants;
import com.hmdp.dto.IncidentReport;
import com.hmdp.entity.Incident;
import com.hmdp.enums.IncidentSeverity;
import com.hmdp.enums.IncidentSource;
import com.hmdp.enums.IncidentStatus;
import com.hmdp.enums.IncidentType;
import com.hmdp.service.IncidentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 故障事件状态检测器：只负责读取既有检测结果并驱动 Incident 的 OPEN / UPDATE / RESOLVE。
 *
 * <p>
 * 本类<b>不做任何阈值判断</b>：
 * </p>
 * <ul>
 *   <li>消费者心跳超时(30s)、Pending 堆积(PENDING_ALERT_THRESHOLD)、消费停滞(DEGRADED 判定)
 *       全部由现有 {@link ConsumerHealthIndicator#health()} 完成，这里只读取其 status 与
 *       consumer_status/reason 明细，把"不健康"这一既有判定映射为故障事件；</li>
 *   <li>死信恢复判定的依据是死信 Stream 的现存条目，属于既有运行状态读取，
 *       不引入新的故障判定阈值（扫描条数上限只是安全边界）。</li>
 * </ul>
 */
@Slf4j
@Component
public class IncidentDetector {

    @Resource
    private IncidentService incidentService;

    @Resource
    private ConsumerHealthIndicator consumerHealthIndicator;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

        try {
            resolveRecoveredDeadLetters();
        } catch (Exception e) {
            log.warn("死信恢复状态同步失败", e);
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

    // ==================== 死信：按现存条目判定恢复 ====================

    private void resolveRecoveredDeadLetters() {
        List<Incident> openIncidents = incidentService.listOpenIncidents(IncidentType.DEAD_LETTER);
        if (openIncidents.isEmpty()) {
            return;
        }

        Set<Long> vouchersWithDeadLetters = readDeadLetterVoucherIds();
        if (vouchersWithDeadLetters == null) {
            // 死信流不可读或超出扫描上限，无法确认"已无死信"，本轮不做恢复判定
            return;
        }

        for (Incident incident : openIncidents) {
            if (incident.getStatus() != IncidentStatus.OPEN) {
                continue;
            }
            Long voucherId = incident.getRelatedVoucherId();
            if (voucherId != null && !vouchersWithDeadLetters.contains(voucherId)) {
                incidentService.resolve(IncidentType.DEAD_LETTER, incident.getBusinessKey());
            }
        }
    }

    /**
     * 读取死信 Stream 中仍存在死信记录的券 id 集合。
     *
     * @return 券 id 集合；死信流不可读或达到扫描上限时返回 null（表示"无法判定"）
     */
    private Set<Long> readDeadLetterVoucherIds() {
        try {
            List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream()
                    .range(RedisConstants.STREAM_ORDERS_DEAD_KEY,
                            Range.unbounded(),
                            Limit.limit().count(IncidentConstants.DEAD_LETTER_SCAN_LIMIT));

            if (records == null || records.isEmpty()) {
                return Collections.emptySet();
            }
            if (records.size() >= IncidentConstants.DEAD_LETTER_SCAN_LIMIT) {
                log.warn("死信流记录数达到扫描上限 {}，本轮跳过恢复判定",
                        IncidentConstants.DEAD_LETTER_SCAN_LIMIT);
                return null;
            }

            Set<Long> voucherIds = new HashSet<>();
            for (MapRecord<String, Object, Object> record : records) {
                Object voucherId = record.getValue().get("voucherId");
                if (voucherId == null) {
                    continue;
                }
                try {
                    voucherIds.add(Long.valueOf(String.valueOf(voucherId)));
                } catch (NumberFormatException e) {
                    log.warn("死信记录 voucherId 非法，已跳过 messageId={}", record.getId());
                }
            }
            return voucherIds;

        } catch (Exception e) {
            log.warn("死信流扫描失败，本轮跳过恢复判定", e);
            return null;
        }
    }
}
