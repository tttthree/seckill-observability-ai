package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 生成独特的ID 有序
 * 形式为 ：| 时间戳 | 序列号 |
 * 标准雪花算法:  [1位未用] [41位毫秒时间戳] [10位机器码] [12位序列号]
 * Redis全局 ID 用 long 的 64 位实现
 * 高 32 位存时间戳
 * 低 32 位存 Redis 自增序列
 * @author zt
 * @version 1.0
 */
@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     * 2022-01-01 00:00:00（UTC时间）
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);  // 秒，不是毫秒
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2.生成序列号
        //2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2.自增长 默认创建且初始值为0
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}
