package com.hmdp.dto.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hmdp.dto.context.IncidentContext.DatabaseEvidence;
import com.hmdp.dto.context.IncidentContext.IncidentEvidence;
import com.hmdp.dto.context.IncidentContext.QueueEvidence;
import com.hmdp.dto.context.IncidentContext.RecentOrder;
import com.hmdp.dto.context.IncidentContext.RedisEvidence;
import com.hmdp.dto.context.IncidentContext.StockState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IncidentContext v3.0 JSON 契约测试。
 * <p>
 * 关键点：整个契约（含所有嵌套 DTO）必须在全局 NON_NULL 配置下仍能真实输出 null，
 * 时间语义符合冻结契约（built_at 为 UTC，Incident 时间为无时区原值），
 * 且已删除的段落/字段不再出现。
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
                .setContextVersion("v3.0")
                .setBuiltAt(Instant.parse("2026-09-23T10:54:00Z"))
                .setIncident(new IncidentEvidence()
                        .setIncidentId(4L)
                        .setIncidentType("INVENTORY_MISMATCH")
                        .setFirstDetectedAt(LocalDateTime.of(2026, 9, 23, 18, 33, 50))
                        .setResolvedAt(null))
                .setRedis(new RedisEvidence()
                        .setStock(new StockState().setPresent(false).setValue(null)))
                .setDatabase(new DatabaseEvidence()
                        .setVoucherExists(false)
                        .setStock(null)
                        .setRecentOrders(List.of(new RecentOrder().setOrderId(99L))))
                .setQueue(new QueueEvidence().setPendingCount(null))
                .setConsumerHealth(null)
                .setUnavailableSources(List.of());

        String json = objectMapper.writeValueAsString(context);

        // 嵌套 DTO 的 nullable 字段必须真实输出为 null（根级 ALWAYS 不足以覆盖嵌套类）
        assertTrue(json.contains("\"value\":null"), json);
        assertTrue(json.contains("\"resolved_at\":null"), json);
        assertTrue(json.contains("\"stock\":null"), json);
        assertTrue(json.contains("\"pending_count\":null"), json);
        // 未采集的段本身为 null
        assertTrue(json.contains("\"consumer_health\":null"), json);
        assertFalse(json.contains("\"consumer_health\":{"), json);
        assertTrue(json.contains("\"unavailable_sources\":[]"), json);
        // Redis key 名不得出现在契约中
        assertFalse(json.contains("seckill:stock:"), json);
    }

    @Test
    void shouldNotContainRemovedSectionsOrFields() throws Exception {
        IncidentContext context = new IncidentContext()
                .setContextVersion("v3.0")
                .setBuiltAt(Instant.parse("2026-09-23T10:54:00Z"))
                .setIncident(new IncidentEvidence().setIncidentId(4L).setOccurrenceCount(1))
                .setUnavailableSources(List.of());

        String json = objectMapper.writeValueAsString(context);

        // 已随 v3.0 删除的内容不得再出现
        // 注意：unavailable_sources 含 available_sources 子串，相关断言必须带引号
        for (String removed : List.of(
                "\"metrics\"", "\"runtime\"", "\"context_quality\"",
                "\"planned_sources\"", "\"available_sources\"", "\"not_implemented_sources\"",
                "\"truncations\"", "\"notes\"", "\"observed_at\"", "\"business_key\"",
                "\"detected_snapshot_scope\"", "\"recent_previous_incidents\"",
                "\"counter_presence\"", "\"voucher_stock\"", "\"voucher_ordered_users\"",
                "\"dirty_vouchers\"", "\"reconcile_mismatch_marker\"",
                "\"main_stream\"", "\"consumer_group\"", "\"dead_letter_stream\"",
                "\"dead_letter_entries_for_voucher\"", "\"stream_entry_id\"",
                "\"original_message_id\"", "\"source_error\"", "\"logs\"")) {
            assertFalse(json.contains(removed), removed + " should be absent: " + json);
        }
        // 契约刻意不提供消费者组 lag / entries_read
        assertFalse(json.contains("\"lag\":"), json);
        assertFalse(json.contains("\"entries_read\":"), json);
    }

    @Test
    void shouldKeepFrozenTimeSemantics() throws Exception {
        IncidentContext context = new IncidentContext()
                .setContextVersion("v3.0")
                .setBuiltAt(Instant.parse("2026-09-23T10:54:00Z"))
                .setIncident(new IncidentEvidence()
                        .setIncidentId(4L)
                        .setFirstDetectedAt(LocalDateTime.of(2026, 9, 23, 18, 33, 50))
                        // snapshot 中的 epoch millis 原样保留
                        .setDetectedSnapshot(Map.of("detected_at", 1790159934990L)))
                .setUnavailableSources(List.of());

        String json = objectMapper.writeValueAsString(context);

        // built_at 必须是 UTC Instant
        assertTrue(json.contains("\"built_at\":\"2026-09-23T10:54:00Z\""), json);
        // Incident 时间原样输出，不得附加 offset
        assertTrue(json.contains("\"first_detected_at\":\"2026-09-23T18:33:50\""), json);
        assertFalse(json.contains("2026-09-23T18:33:50+08:00"), json);
        // snapshot 的 detected_at 保持 epoch millis
        assertTrue(json.contains("\"detected_at\":1790159934990"), json);
        // recent_orders.create_time 同为无时区原值
        IncidentContext withOrder = new IncidentContext()
                .setBuiltAt(Instant.parse("2026-09-23T10:54:00Z"))
                .setDatabase(new DatabaseEvidence()
                        .setVoucherExists(true)
                        .setRecentOrders(List.of(new RecentOrder()
                                .setOrderId(1L)
                                .setCreateTime(LocalDateTime.of(2026, 9, 23, 18, 33, 50)))))
                .setUnavailableSources(List.of());
        String orderJson = objectMapper.writeValueAsString(withOrder);
        assertTrue(orderJson.contains("\"create_time\":\"2026-09-23T18:33:50\""), orderJson);
    }

    @Test
    void shouldNotExposeUserIdInAnySection() throws Exception {
        IncidentContext context = new IncidentContext()
                .setDatabase(new DatabaseEvidence()
                        .setVoucherExists(true)
                        .setStock(1)
                        .setOrderCount(1L)
                        .setRecentOrders(List.of(new RecentOrder()
                                .setOrderId(1L)
                                .setCreateTime(LocalDateTime.of(2026, 9, 23, 18, 33, 50)))))
                .setUnavailableSources(List.of());

        String json = objectMapper.writeValueAsString(context);

        assertFalse(json.contains("user_id"), json);
        assertFalse(json.contains("userId"), json);
    }

    /**
     * 回归：detected_snapshot 是检测时刻证据，Map 中 key 存在而 value=null 的 entry
     * （redis_stock / deviation）必须在最终 JSON 里显式输出，不能被全局 NON_NULL 的 Map content 语义吞掉。
     * <p>
     * 断言针对真实 JSON 文本与解析后的 JsonNode，只验证 Java 内存 Map 不足以覆盖该 bug。
     */
    @Test
    void shouldPreserveExplicitNullValuesInDetectedSnapshot() throws Exception {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("voucher_id", 13);
        snapshot.put("redis_stock", null);
        snapshot.put("db_stock", 0);
        snapshot.put("deviation", null);

        IncidentContext context = new IncidentContext()
                .setContextVersion("v3.0")
                .setBuiltAt(Instant.parse("2026-09-23T10:54:00Z"))
                .setIncident(new IncidentEvidence()
                        .setIncidentId(10L)
                        .setIncidentType("INVENTORY_MISMATCH")
                        .setDetectedSnapshot(snapshot))
                .setUnavailableSources(List.of());

        String json = objectMapper.writeValueAsString(context);

        JsonNode root = objectMapper.readTree(json);
        JsonNode detectedSnapshot = root.path("incident").path("detected_snapshot");

        assertTrue(detectedSnapshot.isObject(), json);
        // key 本身必须存在，而不是"反序列化拿不到值所以当成 null"
        assertTrue(detectedSnapshot.has("voucher_id"), json);
        assertEquals(13, detectedSnapshot.get("voucher_id").asInt(), json);
        assertTrue(detectedSnapshot.has("redis_stock"), json);
        assertTrue(detectedSnapshot.get("redis_stock").isNull(), json);
        assertTrue(detectedSnapshot.has("deviation"), json);
        assertTrue(detectedSnapshot.get("deviation").isNull(), json);
        assertTrue(detectedSnapshot.has("db_stock"), json);
        assertEquals(0, detectedSnapshot.get("db_stock").asInt(), json);
        // 最直接的证据：原始 JSON 文本里必须显式出现这些 key
        assertTrue(json.contains("\"redis_stock\":null"), json);
        assertTrue(json.contains("\"deviation\":null"), json);
        assertTrue(json.contains("\"db_stock\":0"), json);
    }

    @Test
    void shouldKeepUnavailableSourcesExplicitWhenNotEmpty() throws Exception {
        IncidentContext context = new IncidentContext()
                .setContextVersion("v3.0")
                .setBuiltAt(Instant.parse("2026-09-23T10:54:00Z"))
                .setRedis(null)
                .setUnavailableSources(List.of("redis"));

        String json = objectMapper.writeValueAsString(context);

        assertTrue(json.contains("\"redis\":null"), json);
        assertTrue(json.contains("\"unavailable_sources\":[\"redis\"]"), json);
    }
}
