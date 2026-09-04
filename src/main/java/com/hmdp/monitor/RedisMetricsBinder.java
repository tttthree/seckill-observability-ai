package com.hmdp.monitor;

import com.hmdp.constant.MetricsConstants;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static com.hmdp.constant.MetricsConstants.*;

/**
 * Redis 计数器 → Micrometer Gauge → Prometheus 采集 → Grafana 可视化
 *
 * <h3>指标分类</h3>
 * <pre>
 * 第一类：原始计数器（10 个）— 只增不减，Grafana 用 rate()/increase() 分析趋势
 *   请求层:   requests_received_total
 *   预占层:   reserve_success_total, reserve_error_total,
 *            duplicate_request_total, stock_fail_redis_total
 *   成交层:   commit_success_total, commit_error_total, stock_fail_db_total
 *   消费层:   consume_error_total
 *   对账层:   reconcile_mismatch_total
 *
 * 第二类：关系比率（6 个）— 0~1 小数，直接反映指标间的数学关系
 *   系统健康: success_rate, infra_fail_rate
 *   资源约束: stock_fail_rate, duplicate_rate
 *   链路质量: lua_success_rate, lua_to_order_rate
 *   所有比率均可直接设 Grafana 阈值告警，无需复杂 PromQL
 * </pre>
 *
 * <h3>避免循环依赖</h3>
 * 不实现 MeterBinder（会被 BeanPostProcessor 早期回调导致 Lettuce→Micrometer 环路），
 * 改用 ApplicationReadyEvent 延迟注册，此时所有组件均已就绪。
 *
 * @author zt
 * @version 2.0
 */
@Slf4j
@Component
public class RedisMetricsBinder {

    @Resource
    // 指标注册中心，所有 Meter都要注册到这里
    private MeterRegistry meterRegistry;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String PREFIX = "seckill";

    private static final List<Tag> BASE_TAGS = List.of(
            Tag.of("source", "redis"),
            Tag.of("application", "hm-dianping")
    );

    // ==================== 延迟注册入口 ====================

    // ApplicationReadyEvent 是最后发布的，此时所有 Bean（包括 Redis 连接池、Lettuce等）百分之百初始化完毕，不会再触发循环依赖。
    @EventListener(ApplicationReadyEvent.class)
    public void bindMetrics() {
        bindRawCounters(meterRegistry);
        bindComputedRates(meterRegistry);
        log.info("RedisMetricsBinder 注册完成: 10 个计数器 + 6 个比率 → /actuator/prometheus");
    }

    // ==================== 第一类：原始计数器（10 个） ====================

    private void bindRawCounters(MeterRegistry registry) {
        // 请求层
        counterGauge(registry, "requests_received_total", M_TOTAL_REQUESTS,
                Layer.REQUEST, "秒杀请求入口总数");

        // 预占层（Redis Lua）
        counterGauge(registry, "reserve_success_total", M_RESERVE_SUCCESS,
                Layer.RESERVE, "Lua 预占库存成功次数");
        counterGauge(registry, "reserve_error_total", M_RESERVE_ERROR,
                Layer.RESERVE, "Lua 脚本执行异常次数");
        counterGauge(registry, "duplicate_request_total", M_DUPLICATE_REQUEST,
                Layer.RESERVE, "一人一单拦截次数");
        counterGauge(registry, "stock_fail_redis_total", M_STOCK_FAIL_REDIS,
                Layer.RESERVE, "Redis Lua 库存不足次数");

        // 成交层（DB 事务）
        counterGauge(registry, "commit_success_total", M_COMMIT_SUCCESS,
                Layer.COMMIT, "订单创建成功次数");
        counterGauge(registry, "commit_error_total", M_COMMIT_ERROR,
                Layer.COMMIT, "DB 写入异常次数");
        counterGauge(registry, "stock_fail_db_total", M_STOCK_FAIL_DB,
                Layer.COMMIT, "DB 乐观锁库存不足次数");

        // 消费链路
        counterGauge(registry, "consume_error_total", M_CONSUME_ERROR,
                Layer.CONSUME, "消费者线程异常次数");

        // 两阶段对账告警
        counterGauge(registry, "reconcile_mismatch_total", M_RECONCILE_MISMATCH,
                Layer.RECONCILE, "持续库存不一致确认次数");
    }

    // ==================== 第二类：关系比率（6 个） ====================

    private void bindComputedRates(MeterRegistry registry) {
        List<Tag> tags = withLayer(Layer.COMPUTED);

        // 系统健康
        rateGauge(registry, "success_rate", tags,
                "订单成功率（commit_success / total_requests）",
                () -> safeDiv(read(M_COMMIT_SUCCESS), read(M_TOTAL_REQUESTS)));

        rateGauge(registry, "infra_fail_rate", tags,
                "基础设施失败率（Redis/DB/消费异常之和 / 总请求）",
                () -> safeDiv(read(M_RESERVE_ERROR) + read(M_COMMIT_ERROR) + read(M_CONSUME_ERROR),
                              read(M_TOTAL_REQUESTS)));

        // 资源约束
        // 分母 = stock_fail + commit_success，排除重复拦截和基础设施异常的干扰，
        // 只衡量"真正走到库存检查"的请求中库存不足的比例
        rateGauge(registry, "stock_fail_rate", tags,
                "库存竞争失败率（库存失败 / (库存失败+成交)），排除重复和基础设施干扰",
                () -> {
                    double stockFail = read(M_STOCK_FAIL_REDIS) + read(M_STOCK_FAIL_DB);
                    return safeDiv(stockFail, stockFail + read(M_COMMIT_SUCCESS));
                });

        rateGauge(registry, "duplicate_rate", tags,
                "重复下单率（duplicate_request / 总请求）",
                () -> safeDiv(read(M_DUPLICATE_REQUEST), read(M_TOTAL_REQUESTS)));

        // 链路质量
        rateGauge(registry, "lua_success_rate", tags,
                "Lua 预占成功率（reserve_success / 总请求）",
                () -> safeDiv(read(M_RESERVE_SUCCESS), read(M_TOTAL_REQUESTS)));

        rateGauge(registry, "lua_to_order_rate", tags,
                "Lua→DB 转化率（commit_success / reserve_success），链路损耗核心指标",
                () -> safeDiv(read(M_COMMIT_SUCCESS), read(M_RESERVE_SUCCESS)));
    }

    // ==================== 工具方法 ====================

    /** 注册原始计数器 Gauge */
    private void counterGauge(MeterRegistry registry, String name, String redisKey,
                              String layer, String description) {
        List<Tag> tags = withLayer(layer);
        //Supplier<Double> supplier =() -> read(redisKey);
        Gauge.builder(PREFIX + "_" + name, () -> read(redisKey))
                .tags(tags)
                .description(description)
                .register(registry);
    }

    /** 注册比率 Gauge（值域 0~1） */
    private void rateGauge(MeterRegistry registry, String name, List<Tag> tags,
                           String description, Supplier<Double> supplier) {
        //supplier是一个更复杂的 Lambda（包含多次 read() + 除法逻辑）
        Gauge.builder(PREFIX + "_" + name, supplier::get)
                .tags(tags)
                .description(description)
                .register(registry);
    }

    /** 读取 Redis 计数器值 */
    private double read(String key) {
        try {
            String raw = stringRedisTemplate.opsForValue().get(key);
            if (raw == null || raw.isEmpty()) return 0.0;
            return Double.parseDouble(raw);
        } catch (Exception e) {
            log.warn("读取 Redis 指标失败 key={}", key, e);
            return 0.0;
        }
    }

    /** 安全除法 */
    private static double safeDiv(double a, double b) {
        return b == 0.0 ? 0.0 : a / b;
    }

    /** 拼装完整 Tag 列表 */
    private static List<Tag> withLayer(String layer) {
        return Arrays.asList(
                BASE_TAGS.get(0),   // Tag("source", "redis")
                BASE_TAGS.get(1),   // Tag("application", "hm-dianping")
                Tag.of("layer", layer)  // 动态 Tag
        );
    }

    /** 分层标签值 */
    private static class Layer {
        static final String REQUEST   = "request";
        static final String RESERVE   = "reserve";
        static final String COMMIT    = "commit";
        static final String CONSUME   = "consume";
        static final String RECONCILE = "reconcile";
        static final String COMPUTED  = "computed";
    }
}
