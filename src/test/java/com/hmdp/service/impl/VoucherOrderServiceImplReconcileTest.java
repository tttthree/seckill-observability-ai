package com.hmdp.service.impl;

import com.hmdp.dto.IncidentReport;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.enums.IncidentSeverity;
import com.hmdp.enums.IncidentSource;
import com.hmdp.enums.IncidentType;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IncidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;

import static com.hmdp.constant.RedisConstants.SECKILL_RECONCILE_MISMATCH_KEY;
import static com.hmdp.constant.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.constant.RedisConstants.SECKILL_VOUCHER_DIRTY_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存对账 → 故障事件桥接测试（复用真实 reconcile()，只 mock Redis 与 DB）。
 * <p>
 * 覆盖验收 Case 1/2/3/4 在"检测侧"的行为：无异常不上报、首次偏差不建单、
 * 二次确认后上报、连续多轮重复上报（去重由 IncidentService 负责）、恢复后关闭事件。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VoucherOrderServiceImplReconcileTest {

    private static final String VOUCHER_ID = "1";
    private static final String MISMATCH_KEY = SECKILL_RECONCILE_MISMATCH_KEY + VOUCHER_ID;
    private static final String BUSINESS_KEY = "voucher:" + VOUCHER_ID;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @Mock
    private IncidentService incidentService;

    private VoucherOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "incidentService", incidentService);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    }

    /** Case 1 语义：库存一致时不上报故障，但仍幂等尝试关闭既有事件并清理 Reconcile 状态 */
    @Test
    void shouldNotReportWhenStocksAreConsistent() {
        stubStocks("5", 5);

        service.reconcile();

        verify(incidentService, never()).report(any());
        verify(incidentService).resolve(IncidentType.INVENTORY_MISMATCH, BUSINESS_KEY);
        verify(setOperations).remove(SECKILL_VOUCHER_DIRTY_KEY, VOUCHER_ID);
        verify(stringRedisTemplate).delete(MISMATCH_KEY);
    }

    /** Case 2 前段：首次发现偏差只打标记告警，不建故障事件（两阶段确认） */
    @Test
    void shouldNotReportOnFirstMismatchObservation() {
        stubStocks("5", 7);
        when(valueOperations.get(MISMATCH_KEY)).thenReturn(null);

        service.reconcile();

        verify(incidentService, never()).report(any());
        verify(valueOperations).set(eq(MISMATCH_KEY), anyString(), eq(Duration.ofMinutes(10)));
    }

    /** Case 2：第二轮仍不一致 → 上报 INVENTORY_MISMATCH 故障事件 */
    @Test
    void shouldReportIncidentOnConfirmedMismatch() {
        stubStocks("5", 7);
        when(valueOperations.get(MISMATCH_KEY)).thenReturn("1712345678000");

        service.reconcile();

        ArgumentCaptor<IncidentReport> captor = ArgumentCaptor.forClass(IncidentReport.class);
        verify(incidentService, times(1)).report(captor.capture());
        IncidentReport report = captor.getValue();

        assertEquals(IncidentType.INVENTORY_MISMATCH, report.getIncidentType());
        assertEquals(IncidentSource.RECONCILE, report.getSource());
        assertEquals(IncidentSeverity.HIGH, report.getSeverity());
        assertEquals(BUSINESS_KEY, report.getBusinessKey());
        assertEquals(Long.valueOf(1L), report.getRelatedVoucherId());
        assertNotNull(report.getEvidence());
        assertEquals(5, ((Number) report.getEvidence().get("redis_stock")).intValue());
        assertEquals(7, ((Number) report.getEvidence().get("db_stock")).intValue());
        assertEquals(2, ((Number) report.getEvidence().get("deviation")).intValue());
        assertEquals(Long.valueOf(1L), report.getEvidence().get("voucher_id"));
    }

    /** Case 3：持续异常每轮都上报同一 businessKey，聚合由 IncidentService 保证 */
    @Test
    void shouldReportSameBusinessKeyOnEveryConfirmedRound() {
        stubStocks("5", 7);
        when(valueOperations.get(MISMATCH_KEY)).thenReturn("1712345678000");

        service.reconcile();
        service.reconcile();
        service.reconcile();

        ArgumentCaptor<IncidentReport> captor = ArgumentCaptor.forClass(IncidentReport.class);
        verify(incidentService, times(3)).report(captor.capture());
        for (IncidentReport report : captor.getAllValues()) {
            assertEquals(BUSINESS_KEY, report.getBusinessKey());
        }
        verify(incidentService, never()).resolve(any(), anyString());
    }

    /** Case 4：本轮确认库存一致 → 关闭 OPEN 故障事件（不要求此前存在偏差标记） */
    @Test
    void shouldResolveIncidentWhenStocksRecover() {
        stubStocks("7", 7);

        service.reconcile();

        verify(incidentService).resolve(IncidentType.INVENTORY_MISMATCH, BUSINESS_KEY);
        verify(incidentService, never()).report(any());
    }

    /**
     * Case 4 关键边界：mismatch 标记可能因 10 分钟 TTL 过期或 Redis 重启而丢失，
     * 此时只要本轮确认一致，仍必须幂等尝试关闭对应 OPEN 事件。
     */
    @Test
    void shouldAttemptResolveEvenWhenMismatchMarkerIsMissing() {
        stubStocks("7", 7);
        when(stringRedisTemplate.delete(MISMATCH_KEY)).thenReturn(false); // 标记已不存在

        service.reconcile();

        verify(incidentService).resolve(IncidentType.INVENTORY_MISMATCH, BUSINESS_KEY);
        verify(incidentService, never()).report(any());
        verify(setOperations).remove(SECKILL_VOUCHER_DIRTY_KEY, VOUCHER_ID);
        verify(stringRedisTemplate).delete(MISMATCH_KEY);
    }

    /** 没有脏券时不产生任何故障事件 */
    @Test
    void shouldDoNothingWhenThereIsNoDirtyVoucher() {
        when(setOperations.members(SECKILL_VOUCHER_DIRTY_KEY))
                .thenReturn(Collections.emptySet());

        service.reconcile();

        verify(incidentService, never()).report(any());
        verify(incidentService, never()).resolve(any(), anyString());
    }

    private void stubStocks(String redisStock, int dbStock) {
        when(setOperations.members(SECKILL_VOUCHER_DIRTY_KEY))
                .thenReturn(Collections.singleton(VOUCHER_ID));
        when(valueOperations.get(SECKILL_STOCK_KEY + VOUCHER_ID)).thenReturn(redisStock);
        when(seckillVoucherService.getById(Long.valueOf(VOUCHER_ID)))
                .thenReturn(new SeckillVoucher()
                        .setVoucherId(Long.valueOf(VOUCHER_ID))
                        .setStock(dbStock));
    }
}
