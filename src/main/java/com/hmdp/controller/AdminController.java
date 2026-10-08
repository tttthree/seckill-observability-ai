package com.hmdp.controller;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SeckillActivity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.*;

import static com.hmdp.constant.MetricsConstants.*;
import static com.hmdp.constant.RedisConstants.*;

/**
 * 运维管理接口（秒杀控制、手动对账、实时统计）
 *
 */
@Slf4j
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    private static final String QUEUE_NAME = STREAM_ORDERS_KEY;
    private static final String DEAD_LETTER_QUEUE = STREAM_ORDERS_DEAD_KEY;
    private static final String RECONCILE_KEY = SECKILL_VOUCHER_DIRTY_KEY;

    // ==================== 实时统计 ====================

    /**
     * 查看秒杀券实时状态
     * GET /admin/seckill/{voucherId}/stats
     */
    @GetMapping("/seckill/{voucherId}/stats")
    public Map<String, Object> seckillStats(@PathVariable Long voucherId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("activity", SeckillActivity.status(
                stringRedisTemplate.opsForValue().get(SECKILL_ACTIVE_KEY + voucherId),
                stringRedisTemplate.opsForValue().get(SECKILL_BEGIN_KEY + voucherId),
                stringRedisTemplate.opsForValue().get(SECKILL_END_KEY + voucherId),
                System.currentTimeMillis()));

        String redisStock = stringRedisTemplate.opsForValue()
                .get(SECKILL_STOCK_KEY + voucherId);
        stats.put("redis_stock", redisStock != null ? Integer.parseInt(redisStock) : "MISSING");

        var voucher = seckillVoucherService.getById(voucherId);
        stats.put("db_stock", voucher != null ? voucher.getStock() : "N/A");

        long orderCount = voucherOrderService.lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .count();
        stats.put("order_count", orderCount);

        // Stream Pending 堆积（XPENDING，不是 XLEN）
        try {
            var pending = stringRedisTemplate.opsForStream()
                    .pending(QUEUE_NAME, "g1", Range.unbounded(), 10000L);
            stats.put("stream_pending", pending != null ? pending.size() : 0);
        } catch (Exception e) {
            stats.put("stream_pending", "N/A（Redis 不可用）");
        }

        try {
            Long dead = stringRedisTemplate.opsForStream().size(DEAD_LETTER_QUEUE);
            stats.put("dead_letter_size", dead != null ? dead : 0);
        } catch (Exception e) {
            stats.put("dead_letter_size", "N/A");
        }

        try {
            Set<String> dirty = stringRedisTemplate.opsForSet().members(RECONCILE_KEY);
            stats.put("dirty_voucher_count", dirty != null ? dirty.size() : 0);
        } catch (Exception e) {
            stats.put("dirty_voucher_count", "N/A");
        }

        if (redisStock != null && voucher != null && voucher.getStock() != null) {
            int redis = Integer.parseInt(redisStock);
            int db = voucher.getStock();
            stats.put("consistent", redis == db);
            if (redis != db) {
                stats.put("deviation", db - redis);
            }
        }

        return stats;
    }

    // ==================== 紧急控制 ====================

    /**
     * 紧急停止活动；保留库存和在途预占。
     * POST /admin/seckill/{voucherId}/stop
     */
    @PostMapping("/seckill/{voucherId}/stop")
    public Map<String, Object> stopSeckill(@PathVariable Long voucherId) {
        stringRedisTemplate.opsForValue()
                .set(SECKILL_ACTIVE_KEY + voucherId, "0");

        log.warn("🚨 秒杀已紧急停止 voucherId={}", voucherId);
        return Map.of(
                "success", true,
                "action", "stop",
                "voucher_id", voucherId,
                "message", "活动已停止，库存和在途预占保持不变"
        );
    }

    /**
     * 恢复活动元数据；不得覆盖 Redis 库存。
     * POST /admin/seckill/{voucherId}/resume
     */
    @PostMapping("/seckill/{voucherId}/resume")
    public Map<String, Object> resumeSeckill(@PathVariable Long voucherId) {
        var voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null || voucher.getStock() == null) {
            return Map.of("success", false, "message", "券不存在或已失效");
        }
        if (voucher.getBeginTime() == null || voucher.getEndTime() == null
                || voucher.getBeginTime().isAfter(voucher.getEndTime())) {
            return Map.of("success", false, "message", "活动起止时间缺失或非法");
        }
        Map<String, String> metadata = SeckillActivity.metadata(
                voucherId, voucher.getBeginTime(), voucher.getEndTime());
        stringRedisTemplate.opsForValue().multiSet(metadata);

        log.info("✅ 秒杀已恢复 voucherId={}, stock={}", voucherId, voucher.getStock());
        return Map.of(
                "success", true,
                "action", "resume",
                "voucher_id", voucherId,
                "activity", SeckillActivity.status("1", metadata.get(SECKILL_BEGIN_KEY + voucherId),
                        metadata.get(SECKILL_END_KEY + voucherId), System.currentTimeMillis())
        );
    }

    // ==================== 手动对账 ====================

    /**
     * 手动触发库存对账
     * POST /admin/reconcile/trigger
     */
    @PostMapping("/reconcile/trigger")
    public Map<String, Object> triggerReconcile() {
        try {
            voucherOrderService.reconcile();
            return Map.of("success", true);
        } catch (Exception e) {
            log.error("手动对账异常", e);
            return Map.of("success", false, "message", "对账执行失败，请查看服务日志");
        }
    }

    // ==================== 健康检查 ====================

    /**
     * 检查消费者和队列状态
     * GET /admin/health/queue
     */
    @GetMapping("/health/queue")
    public Map<String, Object> queueHealth() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("queue", QUEUE_NAME);

        try {
            var pending = stringRedisTemplate.opsForStream()
                    .pending(QUEUE_NAME, "g1", Range.unbounded(), 10000L);
            long pendingCount = pending != null ? pending.size() : 0;
            health.put("stream_pending", pendingCount);
            health.put("status", pendingCount < 1000 ? "HEALTHY" : "WARNING");
        } catch (Exception e) {
            health.put("stream_size", "N/A");
            health.put("status", "DOWN");
            health.put("error", "Redis 状态获取失败");
        }

        try {
            Long dead = stringRedisTemplate.opsForStream().size(DEAD_LETTER_QUEUE);
            health.put("dead_letter_size", dead);
        } catch (Exception e) {
            health.put("dead_letter_size", "N/A");
        }

        return health;
    }

    // ==================== 指标管理 ====================

    /**
     * 重置所有秒杀指标计数器（压测后清理脏数据）
     * POST /admin/metrics/reset
     */
    @PostMapping("/metrics/reset")
    public Map<String, Object> resetMetrics() {
        Set<String> keys = new HashSet<>(Arrays.asList(getAllMetricKeys()));
        stringRedisTemplate.delete(keys);
        log.info("指标计数器已重置: {} 个 key", keys.size());
        return Map.of(
                "success", true,
                "deleted_keys", keys.size(),
                "message", "所有秒杀指标已归零，2 小时后自动过期"
        );
    }

    /**
     * 查看当前指标摘要
     * GET /admin/metrics/summary
     */
    @GetMapping("/metrics/summary")
    public Map<String, Object> metricsSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : getAllMetricKeys()) {
            String val = stringRedisTemplate.opsForValue().get(key);
            summary.put(key.replace("seckill:metrics:", ""), val != null ? val : "0");
        }
        return summary;
    }

}
