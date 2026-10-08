package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hmdp.dto.context.IncidentContext;
import com.hmdp.entity.Incident;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.enums.IncidentSeverity;
import com.hmdp.enums.IncidentSource;
import com.hmdp.enums.IncidentStatus;
import com.hmdp.enums.IncidentType;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.monitor.ConsumerHealthIndicator;
import com.hmdp.service.IncidentService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.connection.RedisZSetCommands.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * IncidentContext v3.0 采集策略、部分失败与 JSON 契约测试（不依赖 Redis / MySQL）。
 *
 * <p>
 * 覆盖：三类 Incident 的最小采集范围、单数据源失败不拖垮其它数据源、
 * missing ≠ 0、detected_snapshot 显式 null、隐私（无 user_id）、契约字段收敛。
 * </p>
 */
@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentContextBuilderImplTest {

    private static final long INCIDENT_ID = 4L;
    private static final long VOUCHER_ID = 7L;

    @Mock
    private IncidentService incidentService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private StreamOperations<String, Object, Object> streamOperations;
    @Mock
    private SeckillVoucherMapper seckillVoucherMapper;
    @Mock
    private VoucherOrderMapper voucherOrderMapper;
    @Mock
    private ConsumerHealthIndicator consumerHealthIndicator;

    private IncidentContextBuilderImpl builder;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), VoucherOrder.class);

        builder = new IncidentContextBuilderImpl();
        ReflectionTestUtils.setField(builder, "incidentService", incidentService);
        ReflectionTestUtils.setField(builder, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(builder, "seckillVoucherMapper", seckillVoucherMapper);
        ReflectionTestUtils.setField(builder, "voucherOrderMapper", voucherOrderMapper);
        ReflectionTestUtils.setField(builder, "consumerHealthIndicator", consumerHealthIndicator);
        ReflectionTestUtils.setField(builder, "objectMapper", new ObjectMapper());

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
    }

    /** 复刻应用配置：JavaTimeModule + 全局 NON_NULL（类级 ALWAYS 仍须压过它）。 */
    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(
                        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    }

    // ==================== 公共桩 ====================

    private Incident incident(IncidentType type, String snapshot) {
        return new Incident()
                .setId(INCIDENT_ID)
                .setIncidentType(type)
                .setSeverity(IncidentSeverity.HIGH)
                .setSource(IncidentSource.RECONCILE)
                .setStatus(IncidentStatus.OPEN)
                .setBusinessKey("voucher:" + VOUCHER_ID)
                .setRelatedVoucherId(VOUCHER_ID)
                .setOccurrenceCount(2)
                .setFirstDetectedAt(LocalDateTime.of(2026, 1, 15, 15, 58))
                .setLastDetectedAt(LocalDateTime.of(2026, 1, 15, 16, 4, 30))
                .setTitle("测试故障事件")
                .setDescription("测试用描述")
                .setSnapshot(snapshot);
    }

    private void stubIncident(IncidentType type, String snapshot) {
        when(incidentService.getIncident(INCIDENT_ID)).thenReturn(incident(type, snapshot));
    }

    private void stubRedisHealthy() {
        when(valueOperations.get("seckill:stock:" + VOUCHER_ID)).thenReturn("1");
        when(stringRedisTemplate.hasKey("seckill:order:" + VOUCHER_ID)).thenReturn(true);
        when(setOperations.size("seckill:order:" + VOUCHER_ID)).thenReturn(2L);
        when(setOperations.isMember("seckill:voucher:dirty", String.valueOf(VOUCHER_ID)))
                .thenReturn(true);
        when(valueOperations.get("seckill:reconcile:mismatch:" + VOUCHER_ID)).thenReturn("1768464000000");
    }

    private void stubDatabaseHealthy() {
        when(seckillVoucherMapper.selectById(VOUCHER_ID))
                .thenReturn(new SeckillVoucher().setVoucherId(VOUCHER_ID).setStock(2));
        when(voucherOrderMapper.selectCount(any())).thenReturn(2);
        when(voucherOrderMapper.selectList(any())).thenReturn(List.of(
                new VoucherOrder().setId(640780039339638786L).setUserId(3L).setVoucherId(VOUCHER_ID)
                        .setCreateTime(LocalDateTime.of(2026, 1, 15, 15, 59))));
    }

    private void stubQueueHealthy() {
        when(streamOperations.size("stream.orders.dead")).thenReturn(1L);
        Map<Object, Object> values = new LinkedHashMap<>();
        values.put("voucherId", String.valueOf(VOUCHER_ID));
        values.put("id", "810000000000000009");
        values.put("originalMessageId", "1768465200000-0");
        values.put("failureReason", "retry_exhausted");
        List<MapRecord<String, Object, Object>> records = List.<MapRecord<String, Object, Object>>of(
                StreamRecords.<String, Object, Object>mapBacked(values)
                        .withId(RecordId.of("1768465200000-0")));
        when(streamOperations.reverseRange(anyString(), any(), any(Limit.class)))
                .thenReturn((List) records);
    }

    private void stubConsumerHealthy() {
        when(consumerHealthIndicator.health()).thenReturn(Health.up()
                .withDetail("heartbeat_age_ms", 120L)
                .withDetail("success_heartbeat_age_ms", 4500L)
                .withDetail("consumer_status", "HEALTHY")
                .withDetail("pending_count", 0L)
                .build());
    }

    // ==================== A. INVENTORY_MISMATCH ====================

    @Test
    void inventoryMismatchCollectsOnlyIncidentRedisDatabase() {
        stubIncident(IncidentType.INVENTORY_MISMATCH, null);
        stubRedisHealthy();
        stubDatabaseHealthy();

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNotNull(context.getIncident());
        assertNotNull(context.getRedis());
        assertNotNull(context.getDatabase());
        // 未计划采集的数据源：段为 null，且不得进入 unavailable_sources
        assertNull(context.getQueue());
        assertNull(context.getConsumerHealth());
        assertTrue(context.getUnavailableSources().isEmpty());
        assertEquals("v3.0", context.getContextVersion());
        assertNotNull(context.getBuiltAt());
    }

    // ==================== B. DEAD_LETTER ====================

    @Test
    void deadLetterCollectsAllFiveSources() {
        stubIncident(IncidentType.DEAD_LETTER,
                "{\"message_id\":\"1768465200000-0\",\"voucher_id\":7,\"order_id\":810000000000000009,"
                        + "\"failure_reason\":\"retry_exhausted\",\"max_retry\":3}");
        stubRedisHealthy();
        stubDatabaseHealthy();
        stubQueueHealthy();
        stubConsumerHealthy();

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNotNull(context.getIncident());
        assertNotNull(context.getRedis());
        assertNotNull(context.getDatabase());
        assertNotNull(context.getQueue());
        assertNotNull(context.getConsumerHealth());
        assertTrue(context.getUnavailableSources().isEmpty());
    }

    @Test
    void deadLetterExposesOnlyCurrentVoucherDeadLetters() {
        stubIncident(IncidentType.DEAD_LETTER, null);
        stubRedisHealthy();
        stubDatabaseHealthy();
        stubConsumerHealthy();

        when(streamOperations.size("stream.orders.dead")).thenReturn(2L);
        Map<Object, Object> mine = new LinkedHashMap<>();
        mine.put("voucherId", String.valueOf(VOUCHER_ID));
        mine.put("id", "810000000000000009");
        mine.put("originalMessageId", "1768465200000-0");
        mine.put("failureReason", "retry_exhausted");
        Map<Object, Object> other = new LinkedHashMap<>();
        other.put("voucherId", "999");
        other.put("id", "810000000000000010");
        other.put("originalMessageId", "1768465200000-1");
        other.put("failureReason", "retry_exhausted");
        List<MapRecord<String, Object, Object>> records = List.<MapRecord<String, Object, Object>>of(
                StreamRecords.<String, Object, Object>mapBacked(other)
                        .withId(RecordId.of("1768465200000-1")),
                StreamRecords.<String, Object, Object>mapBacked(mine)
                        .withId(RecordId.of("1768465200000-0")));
        when(streamOperations.reverseRange(anyString(), any(), any(Limit.class)))
                .thenReturn((List) records);

        IncidentContext context = builder.build(INCIDENT_ID);

        assertEquals(1, context.getQueue().getDeadLetters().size());
        assertEquals("1768465200000-0", context.getQueue().getDeadLetters().get(0).getMessageId());
        assertEquals(810000000000000009L, context.getQueue().getDeadLetters().get(0).getOrderId());
    }

    @Test
    void otherVouchersDeadLettersDoNotAppearForCurrentVoucher() {
        // 整个死信流非空（其它券），但当前券没有匹配记录 → dead_letters 必须为空列表
        stubIncident(IncidentType.DEAD_LETTER, null);
        stubRedisHealthy();
        stubDatabaseHealthy();
        stubConsumerHealthy();

        when(streamOperations.size("stream.orders.dead")).thenReturn(3L);
        List<MapRecord<String, Object, Object>> others = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<Object, Object> other = new LinkedHashMap<>();
            other.put("voucherId", "999");
            other.put("id", "81000000000000001" + i);
            other.put("originalMessageId", "1768465200001-" + i);
            other.put("failureReason", "retry_exhausted");
            others.add(StreamRecords.<String, Object, Object>mapBacked(other)
                    .withId(RecordId.of("1768465200001-" + i)));
        }
        when(streamOperations.reverseRange(anyString(), any(), any(Limit.class)))
                .thenReturn((List) others);

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNotNull(context.getQueue());
        assertNotNull(context.getQueue().getDeadLetters());
        assertTrue(context.getQueue().getDeadLetters().isEmpty(),
                "其它券的死信不得出现在当前券的证据里");
    }

    // ==================== C. CONSUMER_UNHEALTHY ====================

    @Test
    void consumerUnhealthyCollectsOnlyIncidentAndConsumerHealth() {
        stubIncident(IncidentType.CONSUMER_UNHEALTHY, null);
        stubConsumerHealthy();

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNotNull(context.getIncident());
        assertNotNull(context.getConsumerHealth());
        // 不采 redis / database / queue
        assertNull(context.getRedis());
        assertNull(context.getDatabase());
        assertNull(context.getQueue());
        assertTrue(context.getUnavailableSources().isEmpty());
    }

    @Test
    void consumerHealthStatusProjectsActuatorStateToSingleValue() {
        stubIncident(IncidentType.CONSUMER_UNHEALTHY, null);
        when(consumerHealthIndicator.health()).thenReturn(Health.down()
                .withDetail("reason", "消费者心跳超时: 41000ms")
                .build());

        IncidentContext context = builder.build(INCIDENT_ID);

        // Health DOWN → DOWN（不再分别输出 actuator status 与 consumer_alive）
        assertEquals("DOWN", context.getConsumerHealth().getStatus());
        assertEquals("消费者心跳超时: 41000ms", context.getConsumerHealth().getReason());
    }

    @Test
    void consumerHealthDegradedKeepsSuccessHeartbeat() {
        stubIncident(IncidentType.CONSUMER_UNHEALTHY, null);
        when(consumerHealthIndicator.health()).thenReturn(Health.up()
                .withDetail("heartbeat_age_ms", 100L)
                .withDetail("success_heartbeat_age_ms", 90000L)
                .withDetail("consumer_status", "DEGRADED")
                .withDetail("pending_count", 12L)
                .build());

        IncidentContext context = builder.build(INCIDENT_ID);

        assertEquals("DEGRADED", context.getConsumerHealth().getStatus());
        assertEquals(90000L, context.getConsumerHealth().getSuccessHeartbeatAgeMs());
        assertEquals(12L, context.getConsumerHealth().getPendingCount());
    }

    // ==================== D. source failure ====================

    @Test
    void redisFailureIsolatesOnlyThatSectionAndIsRecorded() {
        stubIncident(IncidentType.INVENTORY_MISMATCH, null);
        stubDatabaseHealthy();
        when(valueOperations.get("seckill:stock:" + VOUCHER_ID))
                .thenThrow(new RuntimeException("redis connection refused"));

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNull(context.getRedis());
        assertEquals(List.of("redis"), context.getUnavailableSources());
        // 其它数据源继续构建
        assertNotNull(context.getDatabase());
        assertNotNull(context.getIncident());
    }

    @Test
    void databaseFailureIsolatesOnlyThatSectionAndIsRecorded() {
        stubIncident(IncidentType.INVENTORY_MISMATCH, null);
        stubRedisHealthy();
        when(seckillVoucherMapper.selectById(VOUCHER_ID))
                .thenThrow(new RuntimeException("db down"));

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNull(context.getDatabase());
        assertEquals(List.of("database"), context.getUnavailableSources());
        assertNotNull(context.getRedis());
    }

    @Test
    void primaryIncidentReadFailurePropagatesInsteadOfBecomingUnavailable() {
        // Incident 主记录查询异常按运维读失败语义显式失败，不得降级为 unavailable_sources
        when(incidentService.getIncident(INCIDENT_ID))
                .thenThrow(new RuntimeException("database unavailable"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> builder.build(INCIDENT_ID));
        assertEquals("database unavailable", thrown.getMessage());
    }

    @Test
    void missingIncidentReturnsNullForControllerToMap404() {
        when(incidentService.getIncident(INCIDENT_ID)).thenReturn(null);

        assertNull(builder.build(INCIDENT_ID));
    }

    // ==================== E. missing Redis stock ====================

    @Test
    void absentRedisStockIsNullNotZero() {
        stubIncident(IncidentType.INVENTORY_MISMATCH, null);
        stubDatabaseHealthy();
        // stock key 不存在
        when(valueOperations.get("seckill:stock:" + VOUCHER_ID)).thenReturn(null);

        IncidentContext context = builder.build(INCIDENT_ID);

        assertFalse(context.getRedis().getStock().getPresent());
        assertNull(context.getRedis().getStock().getValue());
    }

    @Test
    void absentOrderSetYieldsZeroOrderedUsersNotNull() {
        // 资格 Set 不存在时 SCARD 语义就是 0（正常空集合），不得用 null 表示；
        // "Redis 读取失败"由 redis=null + unavailable_sources 表达，两者不可混用。
        stubIncident(IncidentType.INVENTORY_MISMATCH, null);
        stubDatabaseHealthy();
        stubRedisHealthy();
        when(setOperations.size("seckill:order:" + VOUCHER_ID)).thenReturn(0L);

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNotNull(context.getRedis());
        assertEquals(0L, context.getRedis().getOrderedUserCount());
        assertTrue(context.getUnavailableSources().isEmpty());
    }

    @Test
    void redisReadFailureStillNullsWholeSectionInsteadOfZero() {
        // 读取本身抛异常 → 整个 redis 段为 null 并记入 unavailable_sources（与空集合区分）
        stubIncident(IncidentType.INVENTORY_MISMATCH, null);
        stubDatabaseHealthy();
        stubRedisHealthy();
        when(setOperations.size("seckill:order:" + VOUCHER_ID))
                .thenThrow(new RuntimeException("redis connection refused"));

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNull(context.getRedis());
        assertEquals(List.of("redis"), context.getUnavailableSources());
    }

    @Test
    void absentVoucherDoesNotFabricateZeroStock() {
        stubIncident(IncidentType.INVENTORY_MISMATCH, null);
        stubRedisHealthy();
        when(seckillVoucherMapper.selectById(VOUCHER_ID)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0);
        when(voucherOrderMapper.selectList(any())).thenReturn(Collections.emptyList());

        IncidentContext context = builder.build(INCIDENT_ID);

        assertFalse(context.getDatabase().getVoucherExists());
        assertNull(context.getDatabase().getStock());
    }

    // ==================== F. detected_snapshot 显式 null ====================

    @Test
    void snapshotKeepsExplicitNullValues() throws Exception {
        stubIncident(IncidentType.INVENTORY_MISMATCH,
                "{\"voucher_id\":7,\"redis_stock\":null,\"db_stock\":0,\"deviation\":null}");
        stubRedisHealthy();
        stubDatabaseHealthy();

        IncidentContext context = builder.build(INCIDENT_ID);

        Map<String, Object> snapshot = context.getIncident().getDetectedSnapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.containsKey("redis_stock"));
        assertNull(snapshot.get("redis_stock"));
        assertTrue(snapshot.containsKey("deviation"));
        assertNull(snapshot.get("deviation"));
        // 白名单外的字段不得出现
        assertFalse(snapshot.containsKey("user_id"));
    }

    @Test
    void snapshotOnlyProjectsWhitelistedFields() {
        stubIncident(IncidentType.DEAD_LETTER,
                "{\"message_id\":\"m-1\",\"voucher_id\":7,\"order_id\":9,\"failure_reason\":\"retry_exhausted\","
                        + "\"max_retry\":3,\"user_id\":123,\"internal_note\":\"secret\"}");
        stubRedisHealthy();
        stubDatabaseHealthy();
        stubQueueHealthy();
        stubConsumerHealthy();

        IncidentContext context = builder.build(INCIDENT_ID);

        Map<String, Object> snapshot = context.getIncident().getDetectedSnapshot();
        assertEquals("m-1", snapshot.get("message_id"));
        assertEquals(3, snapshot.get("max_retry"));
        // DEAD_LETTER 白名单刻意排除 user_id
        assertFalse(snapshot.containsKey("user_id"));
        assertFalse(snapshot.containsKey("internal_note"));
    }

    // ==================== G. privacy ====================

    @Test
    void recentOrdersNeverExposeUserIdOrVoucherId() throws Exception {
        stubIncident(IncidentType.INVENTORY_MISMATCH, null);
        stubRedisHealthy();
        stubDatabaseHealthy();

        IncidentContext context = builder.build(INCIDENT_ID);
        String json = mapper().writeValueAsString(context);

        assertEquals(1, context.getDatabase().getRecentOrders().size());
        assertFalse(json.contains("user_id"));
        assertFalse(json.contains("userId"));
        // voucher_id 在 recent_orders 内不重复出现（券维度已由 related_voucher_id 表达）
        JsonNode recent = mapper().readTree(json)
                .path("database").path("recent_orders").get(0);
        assertEquals(2, recent.size());
        assertTrue(recent.has("order_id"));
        assertTrue(recent.has("create_time"));
    }

    // ==================== H. JSON contract ====================

    @Test
    void jsonContractIsSnakeCaseAndFreeOfRemovedSections() throws Exception {
        stubIncident(IncidentType.INVENTORY_MISMATCH, null);
        stubRedisHealthy();
        stubDatabaseHealthy();

        IncidentContext context = builder.build(INCIDENT_ID);

        ObjectMapper mapper = mapper();
        String json = mapper.writeValueAsString(context);
        JsonNode root = mapper.readTree(json);

        // 顶层只剩收敛后的字段
        assertTrue(root.has("context_version"));
        assertTrue(root.has("built_at"));
        assertTrue(root.has("incident"));
        assertTrue(root.has("redis"));
        assertTrue(root.has("database"));
        assertTrue(root.has("queue"));
        assertTrue(root.has("consumer_health"));
        assertTrue(root.has("unavailable_sources"));

        // 已删除的段落/字段不得再出现
        assertFalse(root.has("metrics"));
        assertFalse(root.has("runtime"));
        assertFalse(root.has("context_quality"));
        assertFalse(json.contains("\"planned_sources\""));
        // 注意：unavailable_sources 含 available_sources 子串，必须带引号断言
        assertFalse(json.contains("\"available_sources\""));
        assertFalse(json.contains("\"not_implemented_sources\""));
        assertFalse(json.contains("truncations"));
        assertFalse(json.contains("notes"));
        assertFalse(json.contains("observed_at"));
        assertFalse(json.contains("business_key"));
        assertFalse(json.contains("recent_previous_incidents"));
        assertFalse(json.contains("counter_presence"));
        assertFalse(json.contains("stream_entry_id"));
        assertFalse(json.contains("original_message_id"));

        // 段内字段名 snake_case 正确
        assertTrue(root.path("redis").has("stock"));
        assertTrue(root.path("redis").path("stock").has("present"));
        assertTrue(root.path("redis").has("ordered_user_count"));
        assertTrue(root.path("redis").has("dirty"));
        assertTrue(root.path("redis").has("mismatch_pending"));
        assertTrue(root.path("database").has("voucher_exists"));
        assertTrue(root.path("database").has("order_count"));
        assertTrue(root.path("database").has("recent_orders"));
    }

    @Test
    void jsonContractKeepsExplicitNullsUnderNonNullGlobalInclusion() throws Exception {
        stubIncident(IncidentType.INVENTORY_MISMATCH,
                "{\"voucher_id\":7,\"redis_stock\":null,\"db_stock\":0,\"deviation\":null}");
        stubRedisHealthy();
        stubDatabaseHealthy();

        IncidentContext context = builder.build(INCIDENT_ID);

        // 复刻 application.yaml 的全局 non_null 设置
        ObjectMapper mapper = mapper();
        JsonNode root = mapper.readTree(mapper.writeValueAsString(context));

        // detected_snapshot 里显式 null 的 key 必须保留
        JsonNode snapshot = root.path("incident").path("detected_snapshot");
        assertTrue(snapshot.has("redis_stock"));
        assertTrue(snapshot.get("redis_stock").isNull());
        assertTrue(snapshot.has("deviation"));
        assertTrue(snapshot.get("deviation").isNull());

        // 段整体为 null 时仍必须输出该 key（未计划采集）
        assertTrue(root.has("queue"));
        assertTrue(root.path("queue").isNull());
    }

    @Test
    void incidentEvidenceCarriesNoSourceOrScopeFields() {
        stubIncident(IncidentType.INVENTORY_MISMATCH, null);
        stubRedisHealthy();
        stubDatabaseHealthy();

        IncidentContext context = builder.build(INCIDENT_ID);
        IncidentContext.IncidentEvidence evidence = context.getIncident();

        assertEquals(INCIDENT_ID, evidence.getIncidentId());
        assertEquals("INVENTORY_MISMATCH", evidence.getIncidentType());
        assertEquals("HIGH", evidence.getSeverity());
        assertEquals("OPEN", evidence.getStatus());
        assertEquals(VOUCHER_ID, evidence.getRelatedVoucherId());
        assertEquals(2, evidence.getOccurrenceCount());
    }

    @Test
    void redisEvidenceNeverLeaksKeyNames() throws Exception {
        stubIncident(IncidentType.INVENTORY_MISMATCH, null);
        stubRedisHealthy();
        stubDatabaseHealthy();

        IncidentContext context = builder.build(INCIDENT_ID);
        String json = mapper().writeValueAsString(context);

        assertFalse(json.contains("seckill:stock:"));
        assertFalse(json.contains("seckill:order:"));
        assertFalse(json.contains("seckill:voucher:dirty"));
        assertFalse(json.contains("seckill:reconcile:mismatch:"));
    }
}
