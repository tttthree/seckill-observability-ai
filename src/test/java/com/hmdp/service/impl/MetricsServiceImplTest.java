package com.hmdp.service.impl;

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
import java.util.Set;

import static com.hmdp.constant.MetricsConstants.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 只验证当前真实保留的 {@code runtime_metrics}：Redis 计数器 + 消费者健康状态。
 * <p>
 * 契约刻意只有一段：链路比率不在 JSON 接口重复计算（它们由 Prometheus Gauge 单一提供）。
 */
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
    void shouldExposeRuntimeMetricsAsTheOnlyTopLevelSection() {
        Map<String, Object> metrics = service.getSeckillMetrics();

        // 只剩一段：旧的 AI 特征区块（load_model / expected_model / comparison /
        // capacity_analysis / business_analysis / funnel_analysis / diagnosis / context）已随旧 AI 链删除
        assertEquals(Set.of("runtime_metrics"), metrics.keySet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldProjectRedisCountersIntoRuntimeMetrics() {
        Map<String, Object> runtime =
                (Map<String, Object>) service.getSeckillMetrics().get("runtime_metrics");

        assertEquals(10.0, runtime.get("total_requests"));
        assertEquals(8.0, runtime.get("reserve_success"));
        assertEquals(6.0, runtime.get("order_success"));
        assertEquals(2.0, runtime.get("stock_fail_redis"));
        assertEquals(0.0, runtime.get("stock_fail_db"));
        // 聚合值由两个 stock_fail 子项相加得到
        assertEquals(2.0, runtime.get("stock_fail"));
        assertEquals(1.0, runtime.get("consume_fail"));
        // 基础设施失败 = redis_fail + db_fail + consume_fail
        assertEquals(1.0, runtime.get("infra_fail"));
        assertEquals(1.0, runtime.get("reconcile_mismatch"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAttachConsumerHealthFieldsToRuntimeMetrics() {
        Map<String, Object> runtime =
                (Map<String, Object>) service.getSeckillMetrics().get("runtime_metrics");

        assertEquals(1, runtime.get("consumer_alive"));
        assertEquals(10L, runtime.get("heartbeat_age_ms"));
        assertEquals(20L, runtime.get("success_heartbeat_age_ms"));
        assertEquals("HEALTHY", runtime.get("consumer_status"));
        assertEquals(3L, runtime.get("pending_count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldFallBackToSafeDefaultsWhenHealthDetailsAreMissing() {
        // /actuator/health 返回 DOWN 时通常没有这些明细字段，必须退化为约定的安全默认值
        when(healthIndicator.health()).thenReturn(Health.down().build());

        Map<String, Object> runtime =
                (Map<String, Object>) service.getSeckillMetrics().get("runtime_metrics");

        assertEquals(0, runtime.get("consumer_alive"));
        assertEquals(-1L, runtime.get("heartbeat_age_ms"));
        assertEquals(-1L, runtime.get("success_heartbeat_age_ms"));
        assertEquals("UNKNOWN", runtime.get("consumer_status"));
        assertEquals(0, runtime.get("pending_count"));
    }
}
