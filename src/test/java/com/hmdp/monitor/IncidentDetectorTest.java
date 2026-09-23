package com.hmdp.monitor;

import com.hmdp.config.SeckillProperties;
import com.hmdp.constant.IncidentConstants;
import com.hmdp.dto.IncidentReport;
import com.hmdp.entity.Incident;
import com.hmdp.enums.IncidentSeverity;
import com.hmdp.enums.IncidentSource;
import com.hmdp.enums.IncidentStatus;
import com.hmdp.enums.IncidentType;
import com.hmdp.service.IncidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.connection.RedisZSetCommands.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 故障状态检测器测试：只读取 ConsumerHealthIndicator 的既有判定结果，不复制阈值逻辑。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentDetectorTest {

    @Mock
    private IncidentService incidentService;
    @Mock
    private ConsumerHealthIndicator consumerHealthIndicator;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private IncidentDetector detector;

    @BeforeEach
    void setUp() {
        detector = new IncidentDetector();
        ReflectionTestUtils.setField(detector, "incidentService", incidentService);
        ReflectionTestUtils.setField(detector, "consumerHealthIndicator", consumerHealthIndicator);
        ReflectionTestUtils.setField(detector, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(detector, "seckillProperties", new SeckillProperties());
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(incidentService.listOpenIncidents(IncidentType.DEAD_LETTER))
                .thenReturn(Collections.emptyList());
    }

    /** 健康检查 DOWN（心跳超时/Pending 堆积/线程未启动）→ CRITICAL 故障事件 */
    @Test
    void shouldReportCriticalIncidentWhenConsumerIsDown() {
        when(consumerHealthIndicator.health()).thenReturn(Health.down()
                .withDetail("reason", "消费者心跳超时: 40000ms")
                .build());

        detector.detect();

        IncidentReport report = captureSingleReport();
        assertEquals(IncidentType.CONSUMER_UNHEALTHY, report.getIncidentType());
        assertEquals(IncidentSource.HEALTH_INDICATOR, report.getSource());
        assertEquals(IncidentSeverity.CRITICAL, report.getSeverity());
        assertEquals(IncidentConstants.BUSINESS_KEY_CONSUMER_GROUP, report.getBusinessKey());
        assertEquals("DOWN", report.getEvidence().get("health_status"));
        assertEquals("消费者心跳超时: 40000ms", report.getEvidence().get("reason"));
        verify(incidentService, never()).resolve(IncidentType.CONSUMER_UNHEALTHY,
                IncidentConstants.BUSINESS_KEY_CONSUMER_GROUP);
    }

    /** 健康检查 UP 但 consumer_status=DEGRADED → MEDIUM 故障事件（阈值判断在 HealthIndicator 内） */
    @Test
    void shouldReportMediumIncidentWhenConsumerIsDegraded() {
        when(consumerHealthIndicator.health()).thenReturn(Health.up()
                .withDetail("consumer_status", "DEGRADED")
                .withDetail("success_heartbeat_age_ms", 120000L)
                .withDetail("pending_count", 5L)
                .build());

        detector.detect();

        IncidentReport report = captureSingleReport();
        assertEquals(IncidentSeverity.MEDIUM, report.getSeverity());
        assertEquals("DEGRADED", report.getEvidence().get("consumer_status"));
        assertEquals(5L, report.getEvidence().get("pending_count"));
    }

    /** 健康检查 UP 且 HEALTHY → 关闭既有故障事件 */
    @Test
    void shouldResolveIncidentWhenConsumerIsHealthy() {
        when(consumerHealthIndicator.health()).thenReturn(Health.up()
                .withDetail("heartbeat_age_ms", 100L)
                .withDetail("success_heartbeat_age_ms", 200L)
                .withDetail("consumer_status", "HEALTHY")
                .withDetail("pending_count", 0L)
                .build());

        detector.detect();

        verify(incidentService).resolve(IncidentType.CONSUMER_UNHEALTHY,
                IncidentConstants.BUSINESS_KEY_CONSUMER_GROUP);
        verify(incidentService, never()).report(any());
    }

    /** 死信流中已无该券记录 → 关闭对应故障事件（重放成功后的真实恢复信号） */
    @Test
    void shouldResolveDeadLetterIncidentWhenNoDeadLetterRemains() {
        stubHealthyConsumer();
        when(incidentService.listOpenIncidents(IncidentType.DEAD_LETTER))
                .thenReturn(List.of(openDeadLetterIncident(9L, "voucher:9")));
        when(streamOperations.range(anyString(), any(), any()))
                .thenReturn(List.of(record("8")));

        detector.detect();

        verify(incidentService).resolve(IncidentType.DEAD_LETTER, "voucher:9");
    }

    /** 死信流中仍有该券记录 → 维持 OPEN，不伪造恢复 */
    @Test
    void shouldKeepDeadLetterIncidentOpenWhileDeadLetterRemains() {
        stubHealthyConsumer();
        when(incidentService.listOpenIncidents(IncidentType.DEAD_LETTER))
                .thenReturn(List.of(openDeadLetterIncident(9L, "voucher:9")));
        when(streamOperations.range(anyString(), any(), any()))
                .thenReturn(List.of(record("9")));

        detector.detect();

        verify(incidentService, never()).resolve(IncidentType.DEAD_LETTER, "voucher:9");
    }

    /** 死信记录数达到扫描上限 → 无法确认"已无死信"，不做恢复判定 */
    @Test
    void shouldNotResolveWhenDeadLetterScanReachesLimit() {
        stubHealthyConsumer();
        when(incidentService.listOpenIncidents(IncidentType.DEAD_LETTER))
                .thenReturn(List.of(openDeadLetterIncident(9L, "voucher:9")));
        MapRecord<String, Object, Object> single = record("9");
        when(streamOperations.range(anyString(), any(), any()))
                .thenReturn(Collections.nCopies(IncidentConstants.DEAD_LETTER_SCAN_LIMIT, single));

        detector.detect();

        verify(incidentService, never()).resolve(IncidentType.DEAD_LETTER, "voucher:9");
    }

    /** 死信流不可读 → 保持现状，不误判恢复 */
    @Test
    void shouldNotResolveWhenDeadLetterStreamIsUnreadable() {
        stubHealthyConsumer();
        when(incidentService.listOpenIncidents(IncidentType.DEAD_LETTER))
                .thenReturn(List.of(openDeadLetterIncident(9L, "voucher:9")));
        when(streamOperations.range(anyString(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));

        detector.detect();

        verify(incidentService, never()).resolve(IncidentType.DEAD_LETTER, "voucher:9");
    }

    /** 未启用时不做任何状态同步 */
    @Test
    void shouldDoNothingWhenIncidentLayerIsDisabled() {
        SeckillProperties properties = new SeckillProperties();
        properties.getIncident().setEnabled(false);
        ReflectionTestUtils.setField(detector, "seckillProperties", properties);

        detector.detect();

        verify(incidentService, never()).report(any());
        verify(incidentService, never()).resolve(any(), anyString());
        verify(consumerHealthIndicator, never()).health();
    }

    private void stubHealthyConsumer() {
        when(consumerHealthIndicator.health()).thenReturn(Health.up()
                .withDetail("consumer_status", "HEALTHY")
                .build());
    }

    private IncidentReport captureSingleReport() {
        ArgumentCaptor<IncidentReport> captor = ArgumentCaptor.forClass(IncidentReport.class);
        verify(incidentService).report(captor.capture());
        return captor.getValue();
    }

    private static Incident openDeadLetterIncident(Long voucherId, String businessKey) {
        return new Incident()
                .setIncidentType(IncidentType.DEAD_LETTER)
                .setStatus(IncidentStatus.OPEN)
                .setBusinessKey(businessKey)
                .setRelatedVoucherId(voucherId);
    }

    private static MapRecord<String, Object, Object> record(String voucherId) {
        Map<Object, Object> values = new HashMap<>();
        values.put("voucherId", voucherId);
        return StreamRecords.mapBacked(values).<String>withStreamKey("stream.orders.dead");
    }
}
