package com.hmdp.service.impl;

import com.hmdp.config.SeckillProperties;
import com.hmdp.monitor.ConsumerHealthIndicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static com.hmdp.constant.MetricsConstants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ConsumerHealthIndicator healthIndicator;

    private MetricsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MetricsServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "consumerHealthIndicator", healthIndicator);
        ReflectionTestUtils.setField(service, "seckillProperties", new SeckillProperties());

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (M_TOTAL_REQUESTS.equals(key)) return "10";
            if (M_RESERVE_SUCCESS.equals(key)) return "8";
            if (M_COMMIT_SUCCESS.equals(key)) return "6";
            if (M_STOCK_FAIL_REDIS.equals(key)) return "2";
            if (M_CONSUME_ERROR.equals(key)) return "1";
            if (M_RECONCILE_MISMATCH.equals(key)) return "1";
            return "0";
        });
        when(healthIndicator.health()).thenReturn(Health.up()
                .withDetail("heartbeat_age_ms", 10L)
                .withDetail("success_heartbeat_age_ms", 20L)
                .withDetail("consumer_status", "HEALTHY")
                .withDetail("pending_count", 3L)
                .build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldProjectRuntimeMetricsIntoRatesAndDiagnosisSignals() {
        Map<String, Object> metrics = service.getSeckillMetrics();
        Map<String, Object> runtime = (Map<String, Object>) metrics.get("runtime_metrics");
        Map<String, Object> funnel = (Map<String, Object>) metrics.get("funnel_analysis");
        Map<String, Object> diagnosis = (Map<String, Object>) metrics.get("diagnosis");
        Map<String, Object> aiContext = (Map<String, Object>) diagnosis.get("ai_context");
        Map<String, Object> systemFeatures =
                (Map<String, Object>) aiContext.get("system_features");

        assertEquals(10.0, runtime.get("total_requests"));
        assertEquals(1.0, runtime.get("consume_fail"));
        assertEquals(1.0, runtime.get("reconcile_mismatch"));
        assertEquals(0.75, (double) funnel.get("reserve_to_order_rate"), 0.0001);
        assertEquals(0.25, (double) funnel.get("commit_drop_rate"), 0.0001);
        assertEquals(3L, systemFeatures.get("pending_count"));
        assertTrue(((java.util.List<String>) aiContext.get("symptoms"))
                .contains("inventory_mismatch_detected"));
    }
}
