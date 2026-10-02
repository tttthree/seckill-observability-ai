package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.exception.SeckillExceptions.StockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeadRecoveryPendingTest {
    private VoucherOrderServiceImpl service;
    private IVoucherOrderService transaction;
    private SetOperations<String, String> recovery;
    private final VoucherOrder order = new VoucherOrder().setId(10L).setVoucherId(9L).setUserId(7L);

    @BeforeEach void setup() {
        service = spy(new VoucherOrderServiceImpl());
        transaction = mock(IVoucherOrderService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        recovery = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(recovery);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        when(recovery.remove("seckill:dead:recovery:9", "10")).thenReturn(1L);
        ReflectionTestUtils.setField(service, "voucherOrderService", transaction);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        doReturn(null).when(service).getById(10L);
    }

    @Test void clearsOnlyAfterTransactionProxyReturnsCommitted() {
        AtomicBoolean committed = new AtomicBoolean();
        doAnswer(call -> { committed.set(true); return null; }).when(transaction).createVoucherOrder(order);
        when(recovery.remove("seckill:dead:recovery:9", "10")).thenAnswer(call -> {
            assertTrue(committed.get()); return 1L;
        });
        service.handleVoucherOrder(order);
        verify(recovery).remove("seckill:dead:recovery:9", "10");
    }

    @Test void sameOrderIdAlreadyCommittedIsIdempotentEvenWithNoDbStock() {
        doReturn(order).when(service).getById(10L);
        service.handleVoucherOrder(order);
        verify(transaction, never()).createVoucherOrder(any());
        verify(recovery).remove("seckill:dead:recovery:9", "10");
    }

    @Test void failedReplayDoesNotClearMarker() {
        doThrow(new StockException("库存不足")).when(transaction).createVoucherOrder(order);
        assertThrows(StockException.class, () -> service.handleVoucherOrder(order));
        verify(recovery, never()).remove(anyString(), any());
    }

    @Test void duplicateKeyRequiresThisExactOrderId() {
        doThrow(new DuplicateKeyException("user voucher conflict")).when(transaction).createVoucherOrder(order);
        assertThrows(RuntimeException.class, () -> service.handleVoucherOrder(order));
        verify(recovery, never()).remove(anyString(), any());
    }

    @Test void concurrentSameOrderCommitClearsMarkerAfterConfirmation() {
        doReturn(null, order).when(service).getById(10L);
        doThrow(new DuplicateKeyException("same order committed")).when(transaction).createVoucherOrder(order);
        service.handleVoucherOrder(order);
        verify(recovery).remove("seckill:dead:recovery:9", "10");
    }

    @Test void failedMarkerCleanupMustLeaveMessageForRetry() {
        doReturn(order).when(service).getById(10L);
        when(recovery.remove(anyString(), any())).thenThrow(new RuntimeException("redis unavailable"));
        assertThrows(RuntimeException.class, () -> service.handleVoucherOrder(order));
        verify(transaction, never()).createVoucherOrder(any());
    }
}
