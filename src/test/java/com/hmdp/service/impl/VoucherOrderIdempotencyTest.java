package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.exception.SeckillExceptions.StockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 订单消费幂等语义测试（不依赖 recovery marker / 死信重放）。
 *
 * <p>
 * 守护三条关键行为：
 * </p>
 * <ol>
 *   <li>exact orderId 已存在 → 不再发起事务（幂等成功）；</li>
 *   <li>{@link DuplicateKeyException} 但 exact orderId 不存在 → 必须抛异常等待重试，
 *       <b>不能</b>凭"同用户/同券冲突"误判成功；</li>
 *   <li>并发场景：首次查不存在 → 事务抛 DuplicateKey → 二次确认该 orderId 已存在 → 按幂等完成。</li>
 * </ol>
 */
class VoucherOrderIdempotencyTest {

    private VoucherOrderServiceImpl service;
    private IVoucherOrderService transaction;
    private final VoucherOrder order = new VoucherOrder().setId(10L).setVoucherId(9L).setUserId(7L);

    @BeforeEach
    void setup() {
        service = spy(new VoucherOrderServiceImpl());
        transaction = mock(IVoucherOrderService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        ReflectionTestUtils.setField(service, "voucherOrderService", transaction);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
    }

    /** 1. exact orderId 已落库 → 不再次 createVoucherOrder */
    @Test
    void sameOrderIdAlreadyCommittedIsIdempotent() {
        doReturn(order).when(service).getById(10L);

        service.handleVoucherOrder(order);

        verify(transaction, never()).createVoucherOrder(any());
    }

    /** 2. DuplicateKey 但 exact orderId 不存在 → 必须抛出，保留消息等待重试 */
    @Test
    void duplicateKeyRequiresThisExactOrderId() {
        doReturn(null).when(service).getById(10L);
        doThrow(new DuplicateKeyException("user voucher conflict"))
                .when(transaction).createVoucherOrder(order);

        assertThrows(RuntimeException.class, () -> service.handleVoucherOrder(order));
        verify(transaction).createVoucherOrder(order);
    }

    /** 3. 并发：首次查不存在 → DuplicateKey → 二次确认该 orderId 已存在 → 幂等完成，不抛异常 */
    @Test
    void concurrentSameOrderCommitIsTreatedAsIdempotentAfterConfirmation() {
        doReturn(null, order).when(service).getById(10L);
        doThrow(new DuplicateKeyException("same order committed"))
                .when(transaction).createVoucherOrder(order);

        service.handleVoucherOrder(order);

        verify(transaction).createVoucherOrder(order);
        verify(service, times(2)).getById(10L);
    }

    /** 库存不足（DB 条件扣减失败）必须向上抛出，由调用方决定不 ACK、留给 Pending 重试 */
    @Test
    void stockExceptionPropagatesForPendingRetry() {
        doReturn(null).when(service).getById(10L);
        doThrow(new StockException("库存不足")).when(transaction).createVoucherOrder(order);

        assertThrows(StockException.class, () -> service.handleVoucherOrder(order));
    }

    /** 瞬时错误（DB 超时等）必须包装后向上抛出，绝不吞掉 */
    @Test
    void transientFailurePropagatesAsOrderCreateFailed() {
        doReturn(null).when(service).getById(10L);
        doThrow(new IllegalStateException("db timeout")).when(transaction).createVoucherOrder(order);

        assertThrows(RuntimeException.class, () -> service.handleVoucherOrder(order));
    }
}
