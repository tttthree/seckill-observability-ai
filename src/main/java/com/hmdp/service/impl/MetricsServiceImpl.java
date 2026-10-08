package com.hmdp.service.impl;

import com.hmdp.monitor.ConsumerHealthIndicator;
import com.hmdp.service.MetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.hmdp.constant.MetricsConstants.*;

/**
 * 秒杀运行指标采集服务。
 * <p>
 * 只读取 Redis 真实计数器并附加消费者健康状态，输出单段 {@code runtime_metrics}。
 * <p>
 * 只投影真实读到的运行数据：链路比率不在此重复计算——它们由
 * {@link com.hmdp.monitor.RedisMetricsBinder} 以 Prometheus Gauge 形式暴露（单一来源），
 * 避免同一套公式在 JSON 接口与指标端点两处各写一遍后发生漂移。
 */
@Slf4j
@Service
public class MetricsServiceImpl implements MetricsService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ConsumerHealthIndicator consumerHealthIndicator;

    @Override
    public Map<String, Object> getSeckillMetrics() {

        // ================= runtime metrics（运行时计数）=================
        double totalRequests = get(M_TOTAL_REQUESTS);
        double reserveSuccess = get(M_RESERVE_SUCCESS);
        double orderSuccess = get(M_COMMIT_SUCCESS);
        double stockFailRedis = get(M_STOCK_FAIL_REDIS);
        double stockFailDb = get(M_STOCK_FAIL_DB);
        double stockFail = stockFailRedis + stockFailDb;
        double duplicateRequest = get(M_DUPLICATE_REQUEST);
        // 基础设施失败：Lua 执行异常 + DB 写入异常 + 消费者线程异常
        double redisFail = get(M_RESERVE_ERROR);
        double dbFail = get(M_COMMIT_ERROR);
        double consumeFail = get(M_CONSUME_ERROR);
        double infraFail = redisFail + dbFail + consumeFail;
        double reconcileMismatch = get(M_RECONCILE_MISMATCH);

        Map<String, Object> runtimeMetrics = new LinkedHashMap<>();
        runtimeMetrics.put("total_requests", totalRequests);
        runtimeMetrics.put("reserve_success", reserveSuccess);
        runtimeMetrics.put("order_success", orderSuccess);
        runtimeMetrics.put("stock_fail", stockFail);
        runtimeMetrics.put("stock_fail_redis", stockFailRedis);
        runtimeMetrics.put("stock_fail_db", stockFailDb);
        runtimeMetrics.put("duplicate_request", duplicateRequest);
        runtimeMetrics.put("redis_fail", redisFail);
        runtimeMetrics.put("db_fail", dbFail);
        runtimeMetrics.put("consume_fail", consumeFail);
        runtimeMetrics.put("infra_fail", infraFail);
        runtimeMetrics.put("reconcile_mismatch", reconcileMismatch);

        // 消费者健康状态（一次查询复用同一结果，避免重复调用 health()）
        Health consumerHealth = consumerHealthIndicator.health();
        Map<String, Object> details = consumerHealth.getDetails();
        runtimeMetrics.put("consumer_alive",
                consumerHealth.getStatus().getCode().equals("UP") ? 1 : 0);
        runtimeMetrics.put("heartbeat_age_ms", details.getOrDefault("heartbeat_age_ms", -1L));
        runtimeMetrics.put("success_heartbeat_age_ms",
                details.getOrDefault("success_heartbeat_age_ms", -1L));
        runtimeMetrics.put("consumer_status", details.getOrDefault("consumer_status", "UNKNOWN"));
        runtimeMetrics.put("pending_count", details.getOrDefault("pending_count", 0));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runtime_metrics", runtimeMetrics);
        return result;
    }

    // ================= utils =================
    private double get(String key) {
        String rawValue = stringRedisTemplate.opsForValue().get(key);
        if (rawValue == null || rawValue.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(rawValue);
        } catch (Exception e) {
            log.warn("指标转换错误: key={}, value={}", key, rawValue);
            return 0.0;
        }
    }
}
