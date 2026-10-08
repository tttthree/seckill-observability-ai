package com.hmdp.monitor;

import com.hmdp.config.SeckillProperties;
import com.hmdp.constant.IncidentConstants;
import com.hmdp.dto.IncidentReport;
import com.hmdp.enums.IncidentSeverity;
import com.hmdp.enums.IncidentSource;
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
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 故障状态检测器测试：只读取 ConsumerHealthIndicator 的既有判定结果，不复制阈值逻辑。
 *
 * <p>
 * Resume-Lite 不再实现 DEAD_LETTER 的自动重放 / 补偿 / 恢复闭环，因此本测试只覆盖
 * CONSUMER_UNHEALTHY 的 OPEN / RESOLVE 映射；死信事件保持 OPEN 属明确边界。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentDetectorTest {

    @Mock
    private IncidentService incidentService;
    @Mock
    private ConsumerHealthIndicator consumerHealthIndicator;

    private IncidentDetector detector;

    @BeforeEach
    void setUp() {
        detector = new IncidentDetector();
        ReflectionTestUtils.setField(detector, "incidentService", incidentService);
        ReflectionTestUtils.setField(detector, "consumerHealthIndicator", consumerHealthIndicator);
        ReflectionTestUtils.setField(detector, "seckillProperties", new SeckillProperties());
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

    /** HEALTHY 判定完全来自 HealthIndicator 的 consumer_status，检测器不复制阈值 */
    @Test
    void shouldTrustConsumerStatusFromHealthIndicator() {
        // pending 很高但 HealthIndicator 仍报 HEALTHY：检测器必须照它的判定走，不自行改判
        when(consumerHealthIndicator.health()).thenReturn(Health.up()
                .withDetail("consumer_status", "HEALTHY")
                .withDetail("pending_count", 9999L)
                .build());

        detector.detect();

        verify(incidentService).resolve(IncidentType.CONSUMER_UNHEALTHY,
                IncidentConstants.BUSINESS_KEY_CONSUMER_GROUP);
        verify(incidentService, never()).report(any());
    }

    /** Health UP 但缺少 consumer_status → 视为 UNKNOWN，按不健康上报 MEDIUM */
    @Test
    void shouldTreatMissingConsumerStatusAsUnhealthy() {
        when(consumerHealthIndicator.health()).thenReturn(Health.up()
                .withDetail("reason", "未提供原因")
                .build());

        detector.detect();

        IncidentReport report = captureSingleReport();
        assertEquals(IncidentSeverity.MEDIUM, report.getSeverity());
        assertEquals("UNKNOWN", report.getEvidence().get("consumer_status"));
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

    /** 检测器不再持有任何 Redis / 券服务依赖（死信自动恢复已随 Resume-Lite 移除） */
    @Test
    void shouldNotRequireRedisOrVoucherDependencies() throws Exception {
        for (String field : new String[]{"stringRedisTemplate", "seckillVoucherService"}) {
            assertNull(findField(field), "IncidentDetector 不应再持有依赖: " + field);
        }
    }

    private static java.lang.reflect.Field findField(String name) {
        for (Class<?> type = IncidentDetector.class; type != null; type = type.getSuperclass()) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续向上查找
            }
        }
        return null;
    }

    private IncidentReport captureSingleReport() {
        ArgumentCaptor<IncidentReport> captor = ArgumentCaptor.forClass(IncidentReport.class);
        verify(incidentService).report(captor.capture());
        return captor.getValue();
    }
}
