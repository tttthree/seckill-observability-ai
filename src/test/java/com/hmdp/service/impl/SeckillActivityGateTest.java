package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.interceptor.UserHolder;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillActivityGateTest {
    @ParameterizedTest
    @CsvSource({"1,库存不足", "2,不能重复下单", "3,活动元数据缺失或非法，请联系管理员", "4,活动已停止", "5,活动尚未开始", "6,活动已结束"})
    void mapsActivityCodesAndPassesServerTime(int code, String message) {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        RedisIdWorker ids = mock(RedisIdWorker.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any())).thenReturn((long) code);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "redisIdWorker", ids);
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        try {
            long before = System.currentTimeMillis();
            Result result = service.seckillVoucher(1L);
            assertFalse(result.getSuccess());
            assertEquals(message, result.getErrorMsg());
            ArgumentCaptor<Object> now = ArgumentCaptor.forClass(Object.class);
            verify(redis).execute(any(RedisScript.class), anyList(), eq("1"), eq("7"), any(), now.capture());
            long timestamp = Long.parseLong((String) now.getValue());
            assertTrue(timestamp >= before && timestamp <= System.currentTimeMillis());
        } finally {
            UserHolder.removeUser();
        }
    }
}
