package com.hmdp.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsumerHealthIndicatorTest {
    private ConsumerHealthIndicator indicator(long pending, long mainAge, long successAge) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations streams = mock(StreamOperations.class);
        PendingMessagesSummary summary = mock(PendingMessagesSummary.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(streams.pending("stream.orders", "g1")).thenReturn(summary);
        when(summary.getTotalPendingMessages()).thenReturn(pending);
        ConsumerHealthIndicator indicator = new ConsumerHealthIndicator();
        ReflectionTestUtils.setField(indicator, "stringRedisTemplate", redis);
        indicator.markAlive();
        ReflectionTestUtils.setField(indicator, "lastHeartbeat", new AtomicLong(System.currentTimeMillis() - mainAge));
        ReflectionTestUtils.setField(indicator, "lastSuccessHeartbeat", new AtomicLong(System.currentTimeMillis() - successAge));
        return indicator;
    }

    @Test void idleIsHealthyEvenWithoutRecentAck() {
        Health health = indicator(0, 100, 120000).health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("HEALTHY", health.getDetails().get("consumer_status"));
    }
    @Test void staleMainIsDown() { assertEquals(Status.DOWN, indicator(0, 40000, 100).health().getStatus()); }
    @Test void unackedWorkWithoutProgressIsDegraded() {
        Health health = indicator(1, 100, 120000).health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("DEGRADED", health.getDetails().get("consumer_status"));
    }
    @Test void excessivePendingIsDown() { assertEquals(Status.DOWN, indicator(1001, 100, 100).health().getStatus()); }

    @Test void pendingTaskCannotRefreshMainHeartbeat() throws Exception {
        com.hmdp.service.impl.VoucherOrderServiceImpl service = new com.hmdp.service.impl.VoucherOrderServiceImpl();
        ConsumerHealthIndicator health = indicator(0, 40000, 100);
        ReflectionTestUtils.setField(service, "healthIndicator", health);
        ReflectionTestUtils.setField(service, "seckillProperties", new com.hmdp.config.SeckillProperties());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        Class<?> task = Class.forName("com.hmdp.service.impl.VoucherOrderServiceImpl$PendingHandlerTask");
        java.lang.reflect.Constructor<?> constructor = task.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        ((Runnable) constructor.newInstance(service)).run();
        assertEquals(Status.DOWN, health.health().getStatus());
    }

    @Test void prometheusUsesSameStallConditions() throws Exception {
        String rules = java.nio.file.Files.readString(java.nio.file.Path.of("alert.rules.yml"));
        String expr = rules.substring(rules.indexOf("- alert: ConsumerStalled"), rules.indexOf("# ========== 两阶段"));
        assertTrue(expr.contains("seckill_consumer_health == 1"));
        assertTrue(expr.contains("seckill_consumer_pending_count > 0"));
        assertTrue(expr.contains("seckill_consumer_success_heartbeat_age_ms > 60000"));
        assertTrue(expr.indexOf("seckill_consumer_success_heartbeat_age_ms > 60000")
                < expr.indexOf("seckill_consumer_health == 1"));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        HealthMetricsExporter exporter = new HealthMetricsExporter();
        ReflectionTestUtils.setField(exporter, "meterRegistry", registry);
        ReflectionTestUtils.setField(exporter, "consumerHealthIndicator", indicator(5, 100, 100));
        exporter.register();
        assertEquals(5.0, registry.get("seckill_consumer_pending_count").gauge().value());
        registry.close();
    }
}
