package com.hmdp.cache;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.constant.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.constant.RedisConstants.*;

/**
 * 逻辑过期 + 互斥锁缓存工具
 * @author zt
 * @version 1.0
 */
@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断命中的是否是空值
        if ("".equals(json)) {
            //返回一个错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.不存在，返回错误
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //返回一个错误信息
            return null;
        }
        //6.存在，写入redis
        this.set(key, r, time, unit);
        //7.返回
        return r;
    }

    /**
     * 缓存击穿
     */
    //同一时刻最多 10 个 key在重建 ；锁是按 key 粒度的——lock:shop:{id}，不同店铺互不影响
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.缓存未命中（Redis 重启或新增未预热），循环重试直到其他线程重建完成或自己拿到锁
        while (StrUtil.isBlank(json)) {
            String lockKey = LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(lockKey);
            if (isLock) {
                try {
                    // 双重检查：拿到锁后再查一次，可能其他线程已经重建好了
                    String doubleCheck = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(doubleCheck)) {
                        json = doubleCheck;
                        break;
                    }
                    //去db查店铺
                    R r = dbFallback.apply(id);
                    if (r == null) {
                        // 空值缓存防止穿透
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }
                    this.setWithLogicalExpire(key, r, time, unit);
                    return r;
                } finally {
                    unLock(lockKey);
                }
            }
            // 没抢到锁，短暂休眠后重试（此时缓存大概率已被其他线程重建）
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("缓存重建被中断", e);
            }
        }
        //3.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //4.1.未过期，直接返回店铺信息
            return r;
        }
        //4.2.已过期，需要缓存重建
        //5.缓存重建
        //5.1.获取互斥锁 只会有一个人抢到锁进行刷新操作，其他人直接返回旧值
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //5.2.判断是否获取锁成功
        if (isLock) {
            //5.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //根据id查询指定type类型的数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //5.4.返回过期的商铺信息
        return r;
    }

    /**
     * 尝试获取分布式锁
     * waitTime=0 不等待，leaseTime=10 秒后自动过期（不启用看门狗）
     * @return true=获取成功，false=锁被占用
     */
    private boolean tryLock(String key) {
        RLock lock = redissonClient.getLock(key);
        try {
            return lock.tryLock(0, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放锁
     * 异步重建场景下主线程抢锁、异步线程释放，isHeldByCurrentThread 会返回 false，
     * 因此此处不强制解锁——锁靠 10 秒后自动过期
     */
    private void unLock(String key) {
        RLock lock = redissonClient.getLock(key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
