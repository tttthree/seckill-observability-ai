package com.hmdp.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import com.hmdp.constant.RedisConstants;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消费者线程健康检查（接入 Spring Boot Actuator /health 端点）
 * <p>
 * K8s （自动修）可用此端点做存活探测：/actuator/health → DOWN → 自动重启 Pod
 * K8s 默认 terminationGracePeriodSeconds = 30，Pod 超过这个时间还在跑就直接 SIGKILL
 * 上报的本质是在可能长时间阻塞的地方前面刷新时间戳
 * @author zt
 * @version 3.0
 */
@Slf4j
@Component
public class ConsumerHealthIndicator implements HealthIndicator {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String QUEUE_NAME = RedisConstants.STREAM_ORDERS_KEY;
    private static final String CONSUMER_GROUP = RedisConstants.STREAM_ORDERS_GROUP;
    private static final String DEAD_LETTER_QUEUE = RedisConstants.STREAM_ORDERS_DEAD_KEY;
    private static final int PENDING_ALERT_THRESHOLD = 1000;
    private static final int DEAD_LETTER_ALERT_THRESHOLD = 100;

    /** 消费者线程存活标记（消费者线程定期更新此时间戳） */
    private final AtomicLong lastHeartbeat = new AtomicLong(System.currentTimeMillis());
    /** 成功消费心跳（仅在成功 ACK 后更新，用于区分"活着但不健康"和"活着且正常消费"） */
    private final AtomicLong lastSuccessHeartbeat = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean consumerAlive = new AtomicBoolean(false);

    @Override
    public Health health() {
        try {
            // 1. 检查消费者线程是否存活
            if (!consumerAlive.get()) {
                return Health.down()
                        .withDetail("reason", "消费者线程未启动")
                        .build();
            }

            long heartbeatAge = System.currentTimeMillis() - lastHeartbeat.get();
            if (heartbeatAge > 30_000) {  // 30 秒没有心跳
                return Health.down()
                        .withDetail("reason", "消费者心跳超时: " + heartbeatAge + "ms")
                        .build();
            }

            // 成功消费心跳检查 — 消费者活着但长时间未成功消费（卡死/死锁/Redis 连接池耗尽）
            long successAge = System.currentTimeMillis() - lastSuccessHeartbeat.get();
            String consumerStatus = successAge > 60_000 ? "DEGRADED" : "HEALTHY";

            // 2. 检查 Redis Stream Pending 堆积
            //    同时提取 pending_count 供 MetricsService 使用（一次查询，两处消费）
            long pendingCount = 0;
            try {
                // .pending(key, group,...) XPENDING  PEL 里已投递未ACK的消息数量
                PendingMessagesSummary summary = stringRedisTemplate.opsForStream()
                        .pending(QUEUE_NAME, CONSUMER_GROUP);
                pendingCount = summary != null ? summary.getTotalPendingMessages() : 0;
            } catch (Exception e) {
                // Redis 挂了，但消费者可能还活着
                return Health.down()
                        .withDetail("reason", "Redis 连接异常，无法检查 Pending")
                        .withDetail("redis_error", e.getMessage())
                        .build();
            }

            if (pendingCount > PENDING_ALERT_THRESHOLD) {
                return Health.down()
                        .withDetail("reason", "Stream Pending 堆积: " + pendingCount)
                        .withDetail("pending_count", pendingCount)
                        .withDetail("threshold", PENDING_ALERT_THRESHOLD)
                        .build();
            }

            // 3. 检查死信队列是否有新增
            try {
                // .size(key)  XLEN  死信队列 stream.orders.dead  Stream 里所有消息数量
                Long deadCount = stringRedisTemplate.opsForStream()
                        .size(DEAD_LETTER_QUEUE);
                if (deadCount != null && deadCount > DEAD_LETTER_ALERT_THRESHOLD) {
                    log.warn("死信队列堆积: {}", deadCount);
                }
            } catch (Exception ignored) {
                // 死信检查不影响整体健康判断
            }

            return Health.up()
                    .withDetail("heartbeat_age_ms", heartbeatAge)
                    .withDetail("success_heartbeat_age_ms", successAge)
                    .withDetail("consumer_status", consumerStatus)
                    .withDetail("consumer_alive", true)
                    .withDetail("pending_count", pendingCount)
                    .build();

        } catch (Exception e) {
            return Health.down()
                    .withDetail("reason", "健康检查异常: " + e.getMessage())
                    .build();
        }
    }

    // ==================== 消费者线程调用的更新方法 ====================

    public void markAlive() {
        consumerAlive.set(true);
        lastHeartbeat.set(System.currentTimeMillis());
    }

    public void markStopped() {
        consumerAlive.set(false);
    }

    /** 成功消费心跳（仅在成功 ACK 后调用，区分"活着"与"正常工作"） */
    public void markSuccess() {
        lastSuccessHeartbeat.set(System.currentTimeMillis());
    }
}
