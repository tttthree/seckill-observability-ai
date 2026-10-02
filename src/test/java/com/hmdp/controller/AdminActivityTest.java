package com.hmdp.controller;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.SeckillActivity;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminActivityTest {
    @Test void missingStockIsExplicitlyMissingInStats() {
        AdminController controller = new AdminController();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ISeckillVoucherService vouchers = mock(ISeckillVoucherService.class);
        com.hmdp.service.IVoucherOrderService orders = mock(com.hmdp.service.IVoucherOrderService.class);
        com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<com.hmdp.entity.VoucherOrder> query =
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        when(redis.opsForValue()).thenReturn(values);
        when(vouchers.getById(1L)).thenReturn(new SeckillVoucher().setStock(0));
        when(orders.lambdaQuery()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        when(query.count()).thenReturn(0);
        ReflectionTestUtils.setField(controller, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(controller, "seckillVoucherService", vouchers);
        ReflectionTestUtils.setField(controller, "voucherOrderService", orders);
        Map<String, Object> stats = controller.seckillStats(1L);
        assertEquals("MISSING", stats.get("redis_stock"));
        assertFalse(stats.containsKey("consistent"));
    }
    @Test void stopAndResumeNeverOverwriteReservedStock() {
        AdminController controller = new AdminController();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ISeckillVoucherService vouchers = mock(ISeckillVoucherService.class);
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(controller, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(controller, "seckillVoucherService", vouchers);
        when(vouchers.getById(1L)).thenReturn(new SeckillVoucher().setVoucherId(1L).setStock(100)
                .setBeginTime(LocalDateTime.now().minusHours(1)).setEndTime(LocalDateTime.now().plusHours(1)));
        controller.stopSeckill(1L);
        verify(values).set("seckill:active:1", "0");
        controller.resumeSeckill(1L);
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(values).multiSet(metadata.capture());
        assertEquals("1", metadata.getValue().get("seckill:active:1"));
        assertTrue(metadata.getValue().containsKey("seckill:begin:1"));
        assertTrue(metadata.getValue().containsKey("seckill:end:1"));
        assertFalse(metadata.getValue().containsKey("seckill:stock:1"));
        verify(values, never()).set(eq("seckill:stock:1"), anyString());
    }

    @Test void statsActivityBoundariesMatchLuaGate() {
        assertEquals("METADATA_MISSING", SeckillActivity.status(null, "100", "200", 150));
        assertEquals("METADATA_MISSING", SeckillActivity.status("1", "invalid", "200", 150));
        assertEquals("STOPPED", SeckillActivity.status("0", "100", "200", 150));
        assertEquals("NOT_STARTED", SeckillActivity.status("1", "100", "200", 99));
        assertEquals("ACTIVE", SeckillActivity.status("1", "100", "200", 100));
        assertEquals("ACTIVE", SeckillActivity.status("1", "100", "200", 200));
        assertEquals("ENDED", SeckillActivity.status("1", "100", "200", 201));
    }
}
