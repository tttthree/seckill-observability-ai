package com.hmdp.service.impl;

import com.hmdp.entity.Voucher;
import com.hmdp.service.ISeckillVoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoucherActivityTest {
    @Test void creationWritesStockAndCompleteMetadataTogether() {
        VoucherServiceImpl service = spy(new VoucherServiceImpl());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ISeckillVoucherService vouchers = mock(ISeckillVoucherService.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "seckillVoucherService", vouchers);
        when(redis.opsForValue()).thenReturn(values);
        doReturn(true).when(service).save(any(Voucher.class));
        when(vouchers.save(any())).thenReturn(true);
        LocalDateTime begin = LocalDateTime.of(2026, 10, 2, 10, 0);
        Voucher voucher = new Voucher().setId(3L).setStock(4).setBeginTime(begin).setEndTime(begin.plusHours(1));
        service.addSeckillVoucher(voucher);
        ArgumentCaptor<Map<String, String>> captured = ArgumentCaptor.forClass(Map.class);
        verify(values).multiSet(captured.capture());
        assertEquals(4, captured.getValue().size());
        assertEquals("4", captured.getValue().get("seckill:stock:3"));
        assertEquals("1", captured.getValue().get("seckill:active:3"));
        assertEquals(String.valueOf(begin.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
                captured.getValue().get("seckill:begin:3"));
    }

    @Test void invalidMetadataCannotCreatePartiallyInitializedVoucher() {
        VoucherServiceImpl service = spy(new VoucherServiceImpl());
        Voucher voucher = new Voucher().setStock(4);
        assertThrows(IllegalArgumentException.class, () -> service.addSeckillVoucher(voucher));
        verify(service, never()).save(any(Voucher.class));
    }
}
