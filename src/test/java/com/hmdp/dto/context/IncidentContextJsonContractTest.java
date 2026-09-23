package com.hmdp.dto.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hmdp.dto.context.IncidentContext.ContextQuality;
import com.hmdp.dto.context.IncidentContext.ConsumerGroupSummary;
import com.hmdp.dto.context.IncidentContext.DatabaseEvidence;
import com.hmdp.dto.context.IncidentContext.IncidentEvidence;
import com.hmdp.dto.context.IncidentContext.MetricsEvidence;
import com.hmdp.dto.context.IncidentContext.QueueEvidence;
import com.hmdp.dto.context.IncidentContext.RecentOrder;
import com.hmdp.dto.context.IncidentContext.RedisEvidence;
import com.hmdp.dto.context.IncidentContext.RedisScalarValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IncidentContext JSON 契约测试。
 * <p>
 * 关键点：整个契约（含所有嵌套 DTO）必须在全局 NON_NULL 配置下仍能真实输出 null，
 * 并且时间语义符合冻结契约（built_at 为 UTC，Incident 时间为无时区原值）。
 */
class IncidentContextJsonContractTest {

    /** 模拟应用的全局配置：spring.jackson.default-property-inclusion = non_null */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    void shouldSerializeNestedNullableFieldsAsExplicitNull() throws Exception {
        IncidentContext context = new IncidentContext()
                .setContextVersion("v2-2.1")
                .setBuiltAt(Instant.parse("2026-09-23T10:54:00Z"))
                .setIncident(new IncidentEvidence()
                        .setIncidentId(4L)
                        .setIncidentType("INVENTORY_MISMATCH")
                        .setFirstDetectedAt(LocalDateTime.of(2026, 9, 23, 18, 33, 50))
                        .setResolvedAt(null)
                        .setRecentPreviousIncidents(null))
                .setMetrics(new MetricsEvidence()
                        .setCounters(Map.of("total_requests", 2.0d))
                        .setCounterPresence(Map.of("total_requests", true, "consume_error", false)))
                .setRedis(new RedisEvidence()
                        .setVoucherStock(new RedisScalarValue()
                                .setKey("seckill:stock:7").setPresent(false).setValue(null)))
                .setDatabase(new DatabaseEvidence()
                        .setSeckillVoucher(null)
                        .setRecentOrders(List.of(new RecentOrder().setOrderId(99L).setVoucherId(7L)))
                        .setRecentOrdersLimit(20))
                .setQueue(new QueueEvidence()
                        .setConsumerGroup(new ConsumerGroupSummary().setName("g1").setConsumersTotal(null)))
                .setConsumerHealth(null)
                .setRuntime(null)
                .setContextQuality(new ContextQuality()
                        .setComplete(true)
                        .setPlannedSources(List.of("incident", "metrics", "redis", "database"))
                        .setAvailableSources(List.of("incident", "metrics", "redis", "database"))
                        .setUnavailableSources(List.of())
                        .setNotImplementedSources(List.of("logs"))
                        .setErrors(List.of())
                        .setTruncations(List.of())
                        .setNotes(List.of("note")));

        String json = objectMapper.writeValueAsString(context);

        // 嵌套 DTO 的 nullable 字段必须真实输出为 null（根级 ALWAYS 不足以覆盖嵌套类）
        assertTrue(json.contains("\"value\":null"), json);
        assertTrue(json.contains("\"resolved_at\":null"), json);
        assertTrue(json.contains("\"recent_previous_incidents\":null"), json);
        assertTrue(json.contains("\"seckill_voucher\":null"), json);
        // 契约刻意不提供 lag / entries_read 字段，不得再次出现（按字段名断言，notes 文本可提及原因）
        assertFalse(json.contains("\"lag\":"), json);
        assertFalse(json.contains("\"entries_read\":"), json);
        // 未计划的段本身为 null，且不出现在 quality 列表中
        assertTrue(json.contains("\"consumer_health\":null"), json);
        assertTrue(json.contains("\"runtime\":null"), json);
        assertTrue(json.contains("\"unavailable_sources\":[]"), json);
        assertTrue(json.contains("\"not_implemented_sources\":[\"logs\"]"), json);
        assertFalse(json.contains("\"consumer_health\":{"), json);
    }

    @Test
    void shouldKeepFrozenTimeSemantics() throws Exception {
        IncidentContext context = new IncidentContext()
                .setContextVersion("v2-2.1")
                .setBuiltAt(Instant.parse("2026-09-23T10:54:00Z"))
                .setIncident(new IncidentEvidence()
                        .setIncidentId(4L)
                        .setFirstDetectedAt(LocalDateTime.of(2026, 9, 23, 18, 33, 50))
                        // snapshot 中的 epoch millis 原样保留
                        .setDetectedSnapshot(Map.of("detected_at", 1790159934990L)))
                .setContextQuality(new ContextQuality().setComplete(true));

        String json = objectMapper.writeValueAsString(context);

        // built_at 必须是 UTC Instant
        assertTrue(json.contains("\"built_at\":\"2026-09-23T10:54:00Z\""), json);
        // Incident 时间原样输出，不得附加 offset
        assertTrue(json.contains("\"first_detected_at\":\"2026-09-23T18:33:50\""), json);
        assertFalse(json.contains("2026-09-23T18:33:50+08:00"), json);
        // snapshot 的 detected_at 保持 epoch millis
        assertTrue(json.contains("\"detected_at\":1790159934990"), json);
    }

    @Test
    void shouldNotExposeUserIdInAnySection() throws Exception {
        IncidentContext context = new IncidentContext()
                .setDatabase(new DatabaseEvidence()
                        .setRecentOrders(List.of(new RecentOrder().setOrderId(1L).setVoucherId(7L)))
                        .setRecentOrdersLimit(20));

        String json = objectMapper.writeValueAsString(context);

        assertFalse(json.contains("user_id"), json);
        assertFalse(json.contains("userId"), json);
    }
}
