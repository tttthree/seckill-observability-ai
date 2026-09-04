package com.hmdp.service.impl;

import com.hmdp.config.SeckillProperties;
import com.hmdp.monitor.ConsumerHealthIndicator;
import com.hmdp.service.MetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hmdp.constant.MetricsConstants.*;

/**
 * AI可诊断指标采集服务（四层语义模型）
 * @author zt
 * @version 1.0
 */
@Slf4j
@Service
public class MetricsServiceImpl implements MetricsService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ConsumerHealthIndicator consumerHealthIndicator;

    @Resource
    private SeckillProperties seckillProperties;

    /** AI 基线对比：保存的运行时指标字段 */
    private static final List<String> AI_BASELINE_RUNTIME_KEYS = List.of(
            "total_requests", "reserve_success", "order_success",
            "stock_fail", "duplicate_request", "infra_fail"
    );

    /** AI 基线对比：保存的漏斗分析字段 */
    private static final List<String> AI_BASELINE_FUNNEL_KEYS = List.of(
            "lua_hit_rate", "reserve_to_order_rate", "commit_drop_rate"
    );

    @Override
    public Map<String, Object> getSeckillMetrics() {

        Map<String, Object> result = new HashMap<>();

        // ================= load model（压测模型）=================
        Map<String, Object> loadModel = new HashMap<>();
        int concurrency = seckillProperties.getLoadModel().getConcurrency();
        int stock = seckillProperties.getLoadModel().getStock();

        loadModel.put("concurrency", (double) concurrency);
        loadModel.put("stock", (double) stock);
        //突发流量
        loadModel.put("pattern", "burst");

        // ================= runtime metrics （运行时数据） =================

        // 总请求数
        double totalRequests = get(M_TOTAL_REQUESTS);

        // Lua 扣Redis库存成功
        double reserveSuccess = get(M_RESERVE_SUCCESS);
        //订单在DB扣减库存成功，订单完成
        double orderSuccess = get(M_COMMIT_SUCCESS);

        double stockFailRedis = get(M_STOCK_FAIL_REDIS);// 库存失败 Lua阶段失败
        double stockFailDb = get(M_STOCK_FAIL_DB);
        double stockFail = stockFailRedis + stockFailDb;

        // Lua 阶段重复下单（一人一单拦截）
        double duplicateRequest = get(M_DUPLICATE_REQUEST);

        // 基础设施失败
        double redisFail = get(M_RESERVE_ERROR);       // Lua 执行异常
        double dbFail = get(M_COMMIT_ERROR);           // DB 写入异常
        double consumeFail = get(M_CONSUME_ERROR);     // 消费者线程异常
        double infraFail = redisFail + dbFail + consumeFail;

        Map<String, Object> runtimeMetrics = new HashMap<>();
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

        // 对账修复次数
        double reconcileFix = get(M_RECONCILE_FIX);
        runtimeMetrics.put("reconcile_fix", reconcileFix);

        // 消费者健康状态
        Health consumerHealth = consumerHealthIndicator.health();
        runtimeMetrics.put("consumer_alive", consumerHealth.getStatus().getCode().equals("UP") ? 1 : 0);
        runtimeMetrics.put("heartbeat_age_ms", consumerHealth.getDetails().getOrDefault("heartbeat_age_ms", -1L));
        runtimeMetrics.put("success_heartbeat_age_ms", consumerHealth.getDetails().getOrDefault("success_heartbeat_age_ms", -1L));
        runtimeMetrics.put("consumer_status", consumerHealth.getDetails().getOrDefault("consumer_status", "UNKNOWN"));
        runtimeMetrics.put("pending_count", consumerHealth.getDetails().getOrDefault("pending_count", 0));

        // ================= expected model （期望值）=================
        Map<String, Object> expectedModel = Map.of(
                "expected_success", stock,
                "expected_fail", concurrency - stock,
                "expected_total", concurrency
        );

        // ================= comparison （偏差分析）=================
        Map<String, Object> comparison = Map.of(
                "success_deviation", orderSuccess - stock,
                "fail_deviation", stockFail - (concurrency - stock)
        );

        // ================= capacity analysis （1.系统能力层）只回答：系统能力怎么样（可计算） =================
        Map<String, Object> capacityAnalysis = new HashMap<>();

        // Redis Lua 成功率（库存+资格获取能力）
        capacityAnalysis.put("redis_lua_success_rate",
                safeDiv(reserveSuccess, totalRequests));

        // 库存竞争失败率：分母用 stockFail + orderSuccess，排除重复拦截和基础设施异常的干扰
        capacityAnalysis.put("stock_fail_rate",
                safeDiv(stockFail, stockFail + orderSuccess));

        // 基础设施失败率
        capacityAnalysis.put("infra_fail_rate",
                safeDiv(infraFail, totalRequests));

        // ================= business analysis（2.业务结果层）  只回答：发生了什么结果=================
        Map<String, Object> businessAnalysis = new HashMap<>();

        businessAnalysis.put("order_success_rate", safeDiv(orderSuccess, totalRequests));
        businessAnalysis.put("order_success_count", orderSuccess);
        businessAnalysis.put("stock_fail_count", stockFail);

        // ================= funnel analysis（3.链路转化层）=================
        Map<String, Object> funnelAnalysis = new HashMap<>();

        funnelAnalysis.put("lua_hit_rate",
                safeDiv(reserveSuccess, totalRequests));
        // Lua → DB 转化率（链路质量）
        funnelAnalysis.put("reserve_to_order_rate",
                safeDiv(orderSuccess, reserveSuccess));

        funnelAnalysis.put("commit_drop_rate",
                1 - safeDiv(orderSuccess, reserveSuccess));

        // ================= diagnosis（4.AI语义输入层）只回答：现象是什么（不推理） =================
        Map<String, Object> diagnosis = new HashMap<>();

        // system features（系统信号，直接复用 capacityAnalysis 已算值）
        Map<String, Object> systemFeatures = new HashMap<>();
        double infraFailRate = (Double) capacityAnalysis.get("infra_fail_rate");

        systemFeatures.put("infra_fail_rate", infraFailRate);

        // business features（业务信号）
        Map<String, Object> businessFeatures = new HashMap<>();

        businessFeatures.put("stock_exhausted_signal",
                stockFailRedis > 0);

        businessFeatures.put("commit_drop_signal",
                orderSuccess < reserveSuccess);

        // ===== symptoms（症状层）纯事实事件 =====
        List<String> symptoms = new ArrayList<>();

        if (orderSuccess < reserveSuccess) {
            symptoms.add("commit_drop_detected");
        }

        if (stockFailRedis > 0) {
            symptoms.add("redis_stock_pressure");
        }

        if (redisFail > 0 || dbFail > 0) {
            symptoms.add("infra_error_detected");
        }

        // ===== AI context（纯特征工程，不做归因决策，归因交由 AI 完成）=====
        Map<String, Object> aiContext = new HashMap<>();

        aiContext.put("system_features", systemFeatures);
        aiContext.put("business_features", businessFeatures);
        aiContext.put("symptoms", symptoms);

        diagnosis.put("ai_context", aiContext);

        // ================= context =================
        result.put("context", Map.of(
                "system", "seckill",
                "env", "production",
                "test_type", "pressure_test",
                "time_window_seconds", 300
        ));

        result.put("load_model", loadModel);
        result.put("runtime_metrics", runtimeMetrics);
        result.put("expected_model", expectedModel);
        result.put("comparison", comparison);
        result.put("capacity_analysis", capacityAnalysis);
        result.put("business_analysis", businessAnalysis);
        result.put("funnel_analysis", funnelAnalysis);
        result.put("diagnosis", diagnosis);

        return result;
    }

    // ================= baseline =================

    /**
     * 保存当前指标为历史基线（供下次 AI 诊断对比）
     */
    @Override
    public void saveBaseline(Map<String, Object> metrics) {
        if (metrics == null || !seckillProperties.getAi().isBaselineEnabled()) return;

        try {
            Map<String, String> baseline = new HashMap<>();

            @SuppressWarnings("unchecked")
            Map<String, Object> runtime = (Map<String, Object>) metrics.get("runtime_metrics");
            if (runtime != null) {
                for (String key : AI_BASELINE_RUNTIME_KEYS) {
                    Object val = runtime.get(key);
                    if (val instanceof Number) {
                        baseline.put(key, String.valueOf(((Number) val).doubleValue()));
                    }
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> funnel = (Map<String, Object>) metrics.get("funnel_analysis");
            if (funnel != null) {
                for (String key : AI_BASELINE_FUNNEL_KEYS) {
                    Object val = funnel.get(key);
                    if (val instanceof Number) {
                        baseline.put(key, String.format("%.4f", ((Number) val).doubleValue()));
                    }
                }
            }
            baseline.put("timestamp", String.valueOf(System.currentTimeMillis()));

            stringRedisTemplate.opsForHash().putAll(
                    seckillProperties.getAi().getBaselineKey(), baseline);
            stringRedisTemplate.expire(
                    seckillProperties.getAi().getBaselineKey(), Duration.ofHours(24));

        } catch (Exception e) {
            log.warn("保存 AI 基线失败", e);
        }
    }

    /**
     * 加载上次保存的历史基线
     */
    @Override
    public Map<String, Object> loadBaseline() {
        try {
            Map<Object, Object> raw = stringRedisTemplate.opsForHash()
                    .entries(seckillProperties.getAi().getBaselineKey());
            if (raw == null || raw.isEmpty()) {
                return Map.of("exists", false);
            }
            Map<String, Object> baseline = new HashMap<>();
            baseline.put("exists", true);
            Map<String, Object> snapshot = new HashMap<>();
            //把每个key 转成 String，原样丢到新 Map 里
            raw.forEach((k, v) -> snapshot.put(String.valueOf(k), v));
            baseline.put("snapshot", snapshot);
            return baseline;
        } catch (Exception e) {
            log.warn("加载 AI 基线失败", e);
            return Map.of("exists", false);
        }
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

    private double safeDiv(double a, double b) {
        return b == 0 ? 0.0 : a / b;
    }
}
