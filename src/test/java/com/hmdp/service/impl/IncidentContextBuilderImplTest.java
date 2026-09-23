package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.constant.ContextConstants;
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
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V2-2 Context Builder 采集策略、部分失败、白名单与有界读取测试（不依赖 Redis / MySQL）。
 */
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

        when(seckillVoucherMapper.selectById(VOUCHER_ID))
                .thenReturn(new SeckillVoucher().setVoucherId(VOUCHER_ID).setStock(1));
        when(voucherOrderMapper.selectCount(any())).thenReturn(2);
        when(voucherOrderMapper.selectList(any())).thenReturn(List.of(
                new VoucherOrder().setId(640780039339638786L).setUserId(3L).setVoucherId(VOUCHER_ID)
                        .setCreateTime(LocalDateTime.of(2026, 9, 23, 18, 33, 49))));
        when(consumerHealthIndicator.health()).thenReturn(Health.up()
                .withDetail("heartbeat_age_ms", 120L)
                .withDetail("consumer_status", "HEALTHY")
                .withDetail("consumer_alive", true)
                .withDetail("pending_count", 0L)
                .build());
        stubHealthyMetrics();
    }

    @Test
    void shouldReturnNullWhenIncidentDoesNotExist() {
        when(incidentService.getIncident(INCIDENT_ID)).thenReturn(null);

        assertNull(builder.build(INCIDENT_ID));
    }

    @Test
    void shouldPropagatePrimaryEvidenceReadFailure() {
        when(incidentService.getIncident(INCIDENT_ID)).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> builder.build(INCIDENT_ID));
    }

    /** INVENTORY_MISMATCH 只计划 incident + metrics + voucher Redis + voucher DB */
    @Test
    void shouldPlanOnlyInventorySourcesForInventoryMismatch() {
        stubInventoryIncident();
        stubRedisReads(true);

        IncidentContext context = builder.build(INCIDENT_ID);

        assertEquals(List.of("incident", "metrics", "redis", "database"),
                context.getContextQuality().getPlannedSources());
        assertEquals(4, context.getContextQuality().getAvailableSources().size());
        assertTrue(context.getContextQuality().getUnavailableSources().isEmpty());
        assertTrue(context.getContextQuality().getComplete());
        assertEquals(List.of("logs"), context.getContextQuality().getNotImplementedSources());

        // 未计划的数据源既不采集也不出现在 quality 中
        assertNull(context.getQueue());
        assertNull(context.getConsumerHealth());
        assertNull(context.getRuntime());
        verify(streamOperations, never()).size(anyString());
        verify(consumerHealthIndicator, never()).health();
    }

    /** DEAD_LETTER 额外计划 queue + consumer_health，但不含 runtime */
    @Test
    void shouldPlanQueueAndHealthForDeadLetter() {
        stubIncident(deadLetterIncident(), List.of());
        stubRedisReads(true);
        stubEmptyQueue();

        IncidentContext context = builder.build(INCIDENT_ID);

        assertEquals(List.of("incident", "metrics", "redis", "database", "queue", "consumer_health"),
                context.getContextQuality().getPlannedSources());
        assertNotNull(context.getQueue());
        assertNotNull(context.getConsumerHealth());
        assertNull(context.getRuntime());
        assertNotNull(context.getQueue().getConsumerGroup());
        assertEquals(Long.valueOf(15L), context.getQueue().getConsumerGroup().getConsumersTotal());
        assertEquals(Long.valueOf(0L), context.getQueue().getConsumerGroup().getPendingTotal());
        assertEquals("1790159932387-0", context.getQueue().getConsumerGroup().getLastDeliveredId());
    }

    /** CONSUMER_UNHEALTHY 计划 runtime，但不做券维度 Redis/DB 查询 */
    @Test
    void shouldPlanRuntimeForConsumerUnhealthy() {
        stubIncident(consumerUnhealthyIncident(), List.of());
        stubRedisReads(true);
        stubEmptyQueue();

        IncidentContext context = builder.build(INCIDENT_ID);

        assertEquals(List.of("incident", "metrics", "queue", "consumer_health", "runtime"),
                context.getContextQuality().getPlannedSources());
        assertNotNull(context.getRuntime());
        assertNull(context.getRedis());
        assertNull(context.getDatabase());
        verify(seckillVoucherMapper, never()).selectById(anyLong());
        verify(voucherOrderMapper, never()).selectList(any());
    }

    /** V2-2.2：incident 段构建失败时，不得出现在 available_sources */
    @Test
    void shouldNotMarkIncidentAvailableWhenItsCollectFails() {
        stubInventoryIncident();
        stubRedisReads(true);

        IncidentContextBuilderImpl spy = spy(builder);
        doThrow(new RuntimeException("incident evidence build failed"))
                .when(spy).readIncidentEvidence(any(Incident.class), any(IncidentContextBuilderImpl.BuildState.class));

        IncidentContext context = spy.build(INCIDENT_ID);

        assertNull(context.getIncident());
        assertFalse(context.getContextQuality().getAvailableSources().contains("incident"),
                "collect 失败的数据源不得出现在 available_sources: " + context.getContextQuality().getAvailableSources());
        assertTrue(context.getContextQuality().getUnavailableSources().contains("incident"));
        assertFalse(context.getContextQuality().getComplete());
    }

    /** V2-2.2：consumer group 子读取失败必须记为 queue error 并使 complete=false，但不丢失其它 queue 数据 */
    @Test
    void shouldRecordQueueErrorWhenConsumerGroupReadFails() {
        stubIncident(consumerUnhealthyIncident(), List.of());
        stubRedisReads(true);
        stubEmptyQueue();
        when(streamOperations.groups("stream.orders"))
                .thenThrow(new RuntimeException("xinfo groups failed: secret-detail"));

        IncidentContext context = builder.build(INCIDENT_ID);

        // 其它 queue 数据保留
        assertNotNull(context.getQueue());
        assertEquals(Boolean.TRUE, context.getQueue().getMainStream().getExists());
        assertEquals(Long.valueOf(4020L), context.getQueue().getMainStream().getLength());
        // 该数据源本身未被整体判定为不可用
        assertFalse(context.getContextQuality().getUnavailableSources().contains("queue"));
        // 但必须记录 queue error 且 complete=false
        assertFalse(context.getContextQuality().getComplete());
        IncidentContext.SourceError error = context.getContextQuality().getErrors().stream()
                .filter(e -> "queue".equals(e.getSource()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 queue source error: " + context.getContextQuality().getErrors()));
        assertEquals("REDIS_ERROR", error.getErrorType());
        assertEquals("redis read failed", error.getMessage());
        assertFalse(error.getMessage().contains("secret-detail"));
    }

    /** V2-2.2：recent_orders 恰好 N 条不算截断，N+1 条才算且对外最多返回 N 条 */
    @Test
    void shouldNotReportRecentOrdersTruncationAtExactlyLimit() {
        stubInventoryIncident();
        stubRedisReads(true);
        when(voucherOrderMapper.selectList(any()))
                .thenReturn(orders(ContextConstants.RECENT_ORDERS_LIMIT));

        IncidentContext context = builder.build(INCIDENT_ID);

        assertEquals(ContextConstants.RECENT_ORDERS_LIMIT, context.getDatabase().getRecentOrders().size());
        assertTrue(context.getContextQuality().getTruncations().stream()
                        .noneMatch(t -> "recent_orders".equals(t.getSource())),
                "恰好 N 条不得判定为截断");
        assertTrue(context.getContextQuality().getComplete());
    }

    @Test
    void shouldReportRecentOrdersTruncationOnlyWhenExceedingLimit() {
        stubInventoryIncident();
        stubRedisReads(true);
        when(voucherOrderMapper.selectList(any()))
                .thenReturn(orders(ContextConstants.RECENT_ORDERS_LIMIT + 1));

        IncidentContext context = builder.build(INCIDENT_ID);

        assertEquals(ContextConstants.RECENT_ORDERS_LIMIT, context.getDatabase().getRecentOrders().size(),
                "对外最多返回 N 条");
        IncidentContext.Truncation truncation = context.getContextQuality().getTruncations().stream()
                .filter(t -> "recent_orders".equals(t.getSource()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("N+1 条必须记录截断"));
        assertEquals(Integer.valueOf(ContextConstants.RECENT_ORDERS_LIMIT), truncation.getLimit());
        assertEquals(Integer.valueOf(ContextConstants.RECENT_ORDERS_LIMIT), truncation.getReturned());
    }

    /** V2-2.2：死信扫描恰好 scanLimit 条不算截断，scanLimit+1 条才算且最多处理 scanLimit 条 */
    @Test
    void shouldNotReportDeadLetterTruncationAtExactlyScanLimit() {
        stubIncident(deadLetterIncident(), List.of());
        stubRedisReads(true);
        stubEmptyQueue();
        stubDeadLetterRecords(deadLetterRecords(ContextConstants.DEAD_LETTER_SCAN_LIMIT));

        IncidentContext context = builder.build(INCIDENT_ID);

        assertTrue(context.getContextQuality().getTruncations().stream()
                        .noneMatch(t -> "dead_letter_scan".equals(t.getSource())),
                "恰好 scanLimit 条不得判定为截断");
        assertTrue(context.getContextQuality().getComplete());
    }

    @Test
    void shouldReportDeadLetterTruncationOnlyWhenExceedingScanLimit() {
        stubIncident(deadLetterIncident(), List.of());
        stubRedisReads(true);
        stubEmptyQueue();
        stubDeadLetterRecords(deadLetterRecords(ContextConstants.DEAD_LETTER_SCAN_LIMIT + 1));

        IncidentContext context = builder.build(INCIDENT_ID);

        IncidentContext.Truncation truncation = context.getContextQuality().getTruncations().stream()
                .filter(t -> "dead_letter_scan".equals(t.getSource()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("scanLimit+1 条必须记录截断"));
        assertEquals(Integer.valueOf(ContextConstants.DEAD_LETTER_SCAN_LIMIT), truncation.getReturned());
    }

    /**
     * V2-2.2：券级 Incident 只按 voucherId 过滤死信——
     * 同券不同 orderId 的条目都应进入结果，snapshot.order_id 仅代表最近一次检测证据。
     */
    @Test
    void shouldCollectAllDeadLetterEntriesOfSameVoucherRegardlessOfOrderId() {
        // snapshot 里的 order_id=99，但同券还有 100/101 两条死信
        stubIncident(deadLetterIncident(), List.of());
        stubRedisReads(true);
        stubEmptyQueue();
        stubDeadLetterRecords(List.of(
                deadLetterRecord("1-0", "7", "101"),
                deadLetterRecord("2-0", "7", "100"),
                deadLetterRecord("3-0", "7", "99"),
                deadLetterRecord("4-0", "8", "77")));

        IncidentContext context = builder.build(INCIDENT_ID);

        List<IncidentContext.DeadLetterEntry> entries = context.getQueue().getDeadLetterEntriesForVoucher();
        assertEquals(3, entries.size(), "同券不同 orderId 的死信条目都应被收集");
        List<Long> collectedOrderIds = new ArrayList<>();
        for (IncidentContext.DeadLetterEntry entry : entries) {
            collectedOrderIds.add(entry.getOrderId());
        }
        assertTrue(collectedOrderIds.containsAll(List.of(99L, 100L, 101L)), collectedOrderIds.toString());
        assertTrue(entries.stream().allMatch(e -> Long.valueOf(7L).equals(e.getVoucherId())));
    }

    /** 无券维度时才退回 snapshot order_id 过滤 */
    @Test
    void shouldFallBackToSnapshotOrderIdWhenVoucherIsAbsent() {
        Incident incident = deadLetterIncident().setRelatedVoucherId(null);
        stubIncident(incident, List.of());
        stubRedisReads(true);
        stubEmptyQueue();
        stubDeadLetterRecords(List.of(
                deadLetterRecord("1-0", "7", "101"),
                deadLetterRecord("2-0", "7", "99")));

        IncidentContext context = builder.build(INCIDENT_ID);

        List<IncidentContext.DeadLetterEntry> entries = context.getQueue().getDeadLetterEntriesForVoucher();
        assertEquals(1, entries.size());
        assertEquals(Long.valueOf(99L), entries.get(0).getOrderId());
    }

    /** absent ≠ 0：key 不存在时必须 present=false 且 value=null */
    @Test
    void shouldMarkAbsentRedisValuesAsNotPresentInsteadOfZero() {
        stubInventoryIncident();
        when(valueOperations.get(anyString())).thenReturn(null);
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
        when(setOperations.isMember(anyString(), anyString())).thenReturn(false);

        IncidentContext context = builder.build(INCIDENT_ID);

        assertFalse(context.getRedis().getVoucherStock().getPresent());
        assertNull(context.getRedis().getVoucherStock().getValue());
        assertFalse(context.getRedis().getVoucherOrderedUsers().getPresent());
        assertNull(context.getRedis().getVoucherOrderedUsers().getCardinality());
        assertFalse(context.getRedis().getDirtyVouchers().getPresent());
        assertNull(context.getRedis().getDirtyVouchers().getMemberCount());
        assertFalse(context.getRedis().getDirtyVouchers().getContainsVoucher());
    }

    /** Redis 失败只让 redis 段降级，其它已计划数据源仍返回 */
    @Test
    void shouldKeepBuildingWhenRedisSourceFails() {
        stubInventoryIncident();
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("redis down"));

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNull(context.getRedis());
        assertTrue(context.getContextQuality().getUnavailableSources().contains("redis"));
        assertFalse(context.getContextQuality().getComplete());
        assertNotNull(context.getDatabase());
        assertNotNull(context.getMetrics());
        assertNotNull(context.getIncident());
    }

    /** Redis 不可用时不得伪造 0 计数器 */
    @Test
    void shouldNotFabricateCountersWhenMetricsSourceFails() {
        stubInventoryIncident();
        stubRedisReads(true);
        when(valueOperations.multiGet(anyList())).thenThrow(new RuntimeException("redis down"));

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNull(context.getMetrics());
        assertTrue(context.getContextQuality().getUnavailableSources().contains("metrics"));
        assertFalse(context.getContextQuality().getComplete());
    }

    /** counters 与 counter_presence 一一对应，缺失计数器如实标记 */
    @Test
    void shouldExposeCounterPresenceAlongsideCounters() {
        stubInventoryIncident();
        stubRedisReads(true);

        IncidentContext context = builder.build(INCIDENT_ID);

        Map<String, Double> counters = context.getMetrics().getCounters();
        Map<String, Boolean> presence = context.getMetrics().getCounterPresence();
        assertEquals(10, counters.size());
        assertEquals(10, presence.size());
        assertEquals(2.0d, counters.get("total_requests").doubleValue());
        assertTrue(presence.get("total_requests"));
        assertEquals(0.0d, counters.get("consume_error").doubleValue());
        assertFalse(presence.get("consume_error"));
    }

    /** snapshot 按类型白名单投影：DEAD_LETTER 必须剔除 user_id */
    @Test
    void shouldProjectOnlyWhitelistedSnapshotFields() {
        Incident incident = deadLetterIncident().setSnapshot(
                "{\"message_id\":\"1-0\",\"voucher_id\":7,\"user_id\":3,\"order_id\":99,"
                        + "\"failure_reason\":\"retry_exhausted\",\"unexpected_field\":\"x\"}");
        stubIncident(incident, List.of());
        stubRedisReads(true);
        stubEmptyQueue();

        IncidentContext context = builder.build(INCIDENT_ID);
        Map<String, Object> snapshot = context.getIncident().getDetectedSnapshot();

        assertNotNull(snapshot);
        assertTrue(snapshot.containsKey("order_id"));
        assertTrue(snapshot.containsKey("failure_reason"));
        assertFalse(snapshot.containsKey("user_id"), "snapshot 白名单必须剔除 user_id");
        assertFalse(snapshot.containsKey("unexpected_field"));
        assertEquals("LATEST_DETECTION", context.getIncident().getDetectedSnapshotScope());
    }

    /** snapshot 解析失败只记 PARSE_ERROR，不影响其它字段 */
    @Test
    void shouldRecordParseErrorWithoutLosingOtherIncidentFields() {
        stubIncident(inventoryIncident().setSnapshot("{not-json"), List.of());
        stubRedisReads(true);

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNull(context.getIncident().getDetectedSnapshot());
        assertEquals(Long.valueOf(INCIDENT_ID), context.getIncident().getIncidentId());
        assertNotNull(context.getIncident().getTitle());
        assertFalse(context.getContextQuality().getComplete());
        IncidentContext.SourceError error = context.getContextQuality().getErrors().get(0);
        assertEquals("incident", error.getSource());
        assertEquals("PARSE_ERROR", error.getErrorType());
    }

    /** errors 不得携带原始异常信息 */
    @Test
    void shouldNotLeakRawExceptionMessageIntoErrors() {
        stubInventoryIncident();
        when(valueOperations.get(anyString()))
                .thenThrow(new RuntimeException("super-secret-db-credential-detail"));

        IncidentContext context = builder.build(INCIDENT_ID);

        IncidentContext.SourceError error = context.getContextQuality().getErrors().get(0);
        assertEquals("REDIS_ERROR", error.getErrorType());
        assertEquals("redis read failed", error.getMessage());
        assertFalse(error.getMessage().contains("super-secret"));
    }

    /** 死信从最新端读取、按券过滤，并记录扫描上限截断（scanLimit+1 条才判定） */
    @Test
    void shouldReadDeadLetterFromNewestEndAndRecordTruncation() {
        stubIncident(deadLetterIncident(), List.of());
        stubRedisReads(true);
        stubEmptyQueue();

        List<MapRecord<String, Object, Object>> records = new ArrayList<>();
        // 第一条属于本券，其余属于其它券：应只保留本券
        records.add(deadLetterRecord("1-0", "7", "99"));
        for (int i = 1; i <= ContextConstants.DEAD_LETTER_SCAN_LIMIT; i++) {
            records.add(deadLetterRecord(i + "-0", "8", "100"));
        }
        stubDeadLetterRecords(records);

        IncidentContext context = builder.build(INCIDENT_ID);

        verify(streamOperations).reverseRange(anyString(), any(), any(Limit.class));
        assertEquals(1, context.getQueue().getDeadLetterEntriesForVoucher().size());
        assertEquals(Long.valueOf(99L), context.getQueue().getDeadLetterEntriesForVoucher().get(0).getOrderId());
        assertEquals("NEWEST", context.getQueue().getDeadLetterStream().getScannedFrom());
        assertTrue(context.getContextQuality().getTruncations().stream()
                .anyMatch(t -> "dead_letter_scan".equals(t.getSource()) && Boolean.TRUE.equals(t.getTruncated())));
    }

    /** recent_orders 不输出 user_id，超过上限记录截断（N+1 条才判定） */
    @Test
    void shouldExcludeUserIdFromRecentOrdersAndRecordTruncation() {
        stubInventoryIncident();
        stubRedisReads(true);
        when(voucherOrderMapper.selectList(any()))
                .thenReturn(orders(ContextConstants.RECENT_ORDERS_LIMIT + 1));

        IncidentContext context = builder.build(INCIDENT_ID);

        IncidentContext.RecentOrder first = context.getDatabase().getRecentOrders().get(0);
        assertEquals(Long.valueOf(0L), first.getOrderId());
        assertEquals(ContextConstants.RECENT_ORDERS_LIMIT, context.getDatabase().getRecentOrders().size());
        assertTrue(context.getContextQuality().getTruncations().stream()
                .anyMatch(t -> "recent_orders".equals(t.getSource())));
    }

    /** recent_previous_incidents 排除当前 Incident */
    @Test
    void shouldExcludeCurrentIncidentFromRecentPreviousIncidents() {
        Incident current = inventoryIncident();
        Incident older = new Incident().setId(3L)
                .setIncidentType(IncidentType.INVENTORY_MISMATCH)
                .setStatus(IncidentStatus.RESOLVED)
                .setSeverity(IncidentSeverity.HIGH)
                .setOccurrenceCount(2);
        stubIncident(current, List.of(current, older));
        stubRedisReads(true);

        IncidentContext context = builder.build(INCIDENT_ID);

        assertEquals(1, context.getIncident().getRecentPreviousIncidents().size());
        assertEquals(Long.valueOf(3L), context.getIncident().getRecentPreviousIncidents().get(0).getIncidentId());
    }

    /** 历史读取失败时只降级该字段，不影响 incident 主字段 */
    @Test
    void shouldTolerateHistoryReadFailure() {
        stubInventoryIncident();
        stubRedisReads(true);
        when(incidentService.listIncidents(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        IncidentContext context = builder.build(INCIDENT_ID);

        assertNull(context.getIncident().getRecentPreviousIncidents());
        assertEquals(Long.valueOf(INCIDENT_ID), context.getIncident().getIncidentId());
        assertFalse(context.getContextQuality().getComplete());
    }

    // ==================== fixtures ====================

    private void stubIncident(Incident incident, List<Incident> history) {
        when(incidentService.getIncident(INCIDENT_ID)).thenReturn(incident);
        when(incidentService.listIncidents(any(), any(), any(), any())).thenReturn(history);
    }

    private void stubInventoryIncident() {
        stubIncident(inventoryIncident(), List.of());
    }

    private void stubRedisReads(boolean present) {
        when(valueOperations.get("seckill:stock:" + VOUCHER_ID)).thenReturn(present ? "1" : null);
        when(valueOperations.get("seckill:reconcile:mismatch:" + VOUCHER_ID)).thenReturn(null);
        when(stringRedisTemplate.hasKey("seckill:order:" + VOUCHER_ID)).thenReturn(present);
        when(stringRedisTemplate.hasKey("seckill:voucher:dirty")).thenReturn(false);
        when(stringRedisTemplate.hasKey("stream.orders")).thenReturn(false);
        when(stringRedisTemplate.hasKey("stream.orders.dead")).thenReturn(false);
        when(setOperations.size("seckill:order:" + VOUCHER_ID)).thenReturn(2L);
        when(setOperations.isMember(anyString(), anyString())).thenReturn(false);
    }

    private void stubHealthyMetrics() {
        List<String> values = new ArrayList<>(Collections.nCopies(10, null));
        values.set(0, "2");   // total_requests
        values.set(1, "2");   // reserve_success
        values.set(4, "2");   // commit_success
        values.set(9, "4");   // reconcile_mismatch
        when(valueOperations.multiGet(anyList())).thenReturn(values);
    }

    private void stubEmptyQueue() {
        when(stringRedisTemplate.hasKey("stream.orders")).thenReturn(true);
        when(streamOperations.size("stream.orders")).thenReturn(4020L);
        when(streamOperations.info("stream.orders")).thenReturn(streamInfo());
        when(streamOperations.groups("stream.orders")).thenReturn(streamGroups());
    }

    private static StreamInfo.XInfoStream streamInfo() {
        return StreamInfo.XInfoStream.fromList(Arrays.asList(
                "length", 4020L,
                "radix-tree-keys", 41L,
                "radix-tree-nodes", 110L,
                "last-generated-id", "1790159932387-0",
                "first-entry", Arrays.asList("1783750775180-0", Arrays.asList("init", "1")),
                "last-entry", Arrays.asList("1790159932387-0", Arrays.asList("init", "1")),
                "groups", 1L));
    }

    private static StreamInfo.XInfoGroups streamGroups() {
        List<Object> group = Arrays.asList(
                "name", "g1",
                "consumers", 15L,
                "pending", 0L,
                "last-delivered-id", "1790159932387-0",
                "entries-read", 4020L,
                "lag", 0L);
        return StreamInfo.XInfoGroups.fromList(Collections.singletonList(group));
    }

    private static MapRecord<String, Object, Object> deadLetterRecord(String entryId, String voucherId, String orderId) {
        Map<Object, Object> values = new HashMap<>();
        values.put("voucherId", voucherId);
        values.put("id", orderId);
        values.put("originalMessageId", "1783750775180-0");
        values.put("failureReason", "retry_exhausted");
        return StreamRecords.mapBacked(values).<String>withStreamKey("stream.orders.dead")
                .withId(RecordId.of(entryId));
    }

    /** 生成 count 条订单（都不含 user_id 输出，但实体上带 userId 以验证白名单） */
    private static List<VoucherOrder> orders(int count) {
        List<VoucherOrder> orders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            orders.add(new VoucherOrder().setId((long) i).setUserId(3L).setVoucherId(VOUCHER_ID)
                    .setCreateTime(LocalDateTime.of(2026, 9, 23, 18, 0, i % 60)));
        }
        return orders;
    }

    /** 死信流存在 + 指定 reverseRange 结果 */
    private void stubDeadLetterRecords(List<MapRecord<String, Object, Object>> records) {
        when(stringRedisTemplate.hasKey("stream.orders.dead")).thenReturn(true);
        when(streamOperations.size("stream.orders.dead")).thenReturn((long) records.size());
        when(streamOperations.reverseRange(anyString(), any(), any(Limit.class))).thenReturn(records);
    }

    /** 生成 count 条死信记录（默认都属于其它券 voucherId=8） */
    private static List<MapRecord<String, Object, Object>> deadLetterRecords(int count) {
        List<MapRecord<String, Object, Object>> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            records.add(deadLetterRecord(i + "-0", "8", String.valueOf(1000 + i)));
        }
        return records;
    }

    private static Incident inventoryIncident() {
        return new Incident()
                .setId(INCIDENT_ID)
                .setIncidentType(IncidentType.INVENTORY_MISMATCH)
                .setSeverity(IncidentSeverity.HIGH)
                .setSource(IncidentSource.RECONCILE)
                .setStatus(IncidentStatus.RESOLVED)
                .setBusinessKey("voucher:" + VOUCHER_ID)
                .setRelatedVoucherId(VOUCHER_ID)
                .setOccurrenceCount(2)
                .setTitle("Redis 与 MySQL 库存持续不一致（voucherId=7）")
                .setDescription("连续两轮对账确认偏差")
                .setFirstDetectedAt(LocalDateTime.of(2026, 9, 23, 18, 33, 50))
                .setLastDetectedAt(LocalDateTime.of(2026, 9, 23, 18, 38, 55))
                .setResolvedAt(LocalDateTime.of(2026, 9, 23, 18, 39, 1))
                .setSnapshot("{\"db_stock\":1,\"deviation\":1,\"voucher_id\":7,"
                        + "\"detected_at\":1790159934990,\"redis_stock\":0}");
    }

    private static Incident deadLetterIncident() {
        return new Incident()
                .setId(INCIDENT_ID)
                .setIncidentType(IncidentType.DEAD_LETTER)
                .setSeverity(IncidentSeverity.HIGH)
                .setSource(IncidentSource.STREAM_CONSUMER)
                .setStatus(IncidentStatus.OPEN)
                .setBusinessKey("voucher:" + VOUCHER_ID)
                .setRelatedVoucherId(VOUCHER_ID)
                .setOccurrenceCount(1)
                .setTitle("订单消息重试超限进入死信队列（voucherId=7）")
                .setFirstDetectedAt(LocalDateTime.of(2026, 9, 23, 18, 33, 50))
                .setLastDetectedAt(LocalDateTime.of(2026, 9, 23, 18, 33, 50))
                .setSnapshot("{\"message_id\":\"1-0\",\"voucher_id\":7,\"order_id\":99,"
                        + "\"failure_reason\":\"retry_exhausted\",\"detected_at\":1790159934990}");
    }

    private static Incident consumerUnhealthyIncident() {
        return new Incident()
                .setId(INCIDENT_ID)
                .setIncidentType(IncidentType.CONSUMER_UNHEALTHY)
                .setSeverity(IncidentSeverity.CRITICAL)
                .setSource(IncidentSource.HEALTH_INDICATOR)
                .setStatus(IncidentStatus.OPEN)
                .setBusinessKey("stream.orders:g1")
                .setOccurrenceCount(1)
                .setTitle("秒杀消费者线程不可用")
                .setFirstDetectedAt(LocalDateTime.of(2026, 9, 23, 18, 33, 50))
                .setLastDetectedAt(LocalDateTime.of(2026, 9, 23, 18, 33, 50))
                .setSnapshot("{\"health_status\":\"DOWN\",\"pending_count\":0,\"checked_at\":1790159934990}");
    }
}
