package com.hmdp.controller;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.*;

import static com.hmdp.constant.MetricsConstants.*;
import static com.hmdp.constant.RedisConstants.*;

/**
 * 运维管理接口（秒杀控制、死信重放、手动对账、实时统计）
 *
 * @author zt
 * @version 3.0
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

        // Redis 库存
        String redisStock = stringRedisTemplate.opsForValue()
                .get(SECKILL_STOCK_KEY + voucherId);
        stats.put("redis_stock", redisStock != null ? Integer.parseInt(redisStock) : 0);

        // DB 库存
        var voucher = seckillVoucherService.getById(voucherId);
        stats.put("db_stock", voucher != null ? voucher.getStock() : "N/A");

        // 已售数量
        long orderCount = voucherOrderService.lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .count();
        stats.put("order_count", orderCount);

        // Stream Pending 堆积（XPENDING，不是 XLEN）
        try {
            //Range.unbounded() — 查所有消息 ID，不限范围
            //.pending() → XPENDING → 返回 PendingMessages 对象 → 只查 PEL 里未 ACK 的消息，.size()拿数量
            var pending = stringRedisTemplate.opsForStream()
                    .pending(QUEUE_NAME, "g1", Range.unbounded(), 10000L);
            stats.put("stream_pending", pending != null ? pending.size() : 0);
        } catch (Exception e) {
            stats.put("stream_pending", "N/A（Redis 不可用）");
        }

        // 死信队列
        try {
            //.size() → XLEN → 返回 Long 总数 → Stream 里全部消息，不管是否已 ACK
            Long dead = stringRedisTemplate.opsForStream().size(DEAD_LETTER_QUEUE);
            stats.put("dead_letter_size", dead != null ? dead : 0);
        } catch (Exception e) {
            stats.put("dead_letter_size", "N/A");
        }

        // 脏券数量
        try {
            //拿 Set 里所有元素
            Set<String> dirty = stringRedisTemplate.opsForSet().members(RECONCILE_KEY);
            stats.put("dirty_voucher_count", dirty != null ? dirty.size() : 0);
        } catch (Exception e) {
            stats.put("dirty_voucher_count", "N/A");
        }

        // Redis- DB 是否一致
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
     * 紧急停止秒杀（Redis 库存置 0，所有新请求被 Lua 拒绝）
     * POST /admin/seckill/{voucherId}/stop
     */
    @PostMapping("/seckill/{voucherId}/stop")
    public Map<String, Object> stopSeckill(@PathVariable Long voucherId) {
        stringRedisTemplate.opsForValue()
                .set(SECKILL_STOCK_KEY + voucherId, "0");

        log.warn("🚨 秒杀已紧急停止 voucherId={}", voucherId);
        return Map.of(
                "success", true,
                "action", "stop",
                "voucher_id", voucherId,
                "message", "Redis 库存已置 0，所有新请求将被拒绝"
        );
    }

    /**
     * 恢复秒杀（重新设置 Redis 库存 = DB 库存）
     * POST /admin/seckill/{voucherId}/resume
     */
    @PostMapping("/seckill/{voucherId}/resume")
    public Map<String, Object> resumeSeckill(@PathVariable Long voucherId) {
        var voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null || voucher.getStock() == null) {
            return Map.of("success", false, "message", "券不存在或已失效");
        }
        stringRedisTemplate.opsForValue()
                .set(SECKILL_STOCK_KEY + voucherId, String.valueOf(voucher.getStock()));

        log.info("✅ 秒杀已恢复 voucherId={}, stock={}", voucherId, voucher.getStock());
        return Map.of(
                "success", true,
                "action", "resume",
                "voucher_id", voucherId,
                "stock", voucher.getStock()
        );
    }

    // ==================== 死信队列管理 ====================

    /**
     * 重放死信队列中的消息到主 Stream
     * POST /admin/dead-letter/replay
     */
    @PostMapping("/dead-letter/replay")
    public Map<String, Object> replayDeadLetters() {
        int count = 0;

        try {
            // XRANGE 直接读，不需要消费者组，不挂 PEL
            var records = stringRedisTemplate.opsForStream()
                    .range(DEAD_LETTER_QUEUE, Range.unbounded());

            if (records != null) {
                for (var record : records) {
                    // 重新投递到主 Stream
                    stringRedisTemplate.opsForStream()
                            .add(QUEUE_NAME, record.getValue());
                    // 从死信队列删除
                    stringRedisTemplate.opsForStream()
                            .delete(DEAD_LETTER_QUEUE, record.getId());
                    count++;
                }
            }
        } catch (Exception e) {
            log.error("死信重放失败", e);
            return Map.of("success", false, "message", e.getMessage());
        }

        log.info("死信重放完成: {} 条消息已重新投递到 {}", count, QUEUE_NAME);
        return Map.of(
                "success", true,
                "replayed_count", count,
                "target_queue", QUEUE_NAME
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
            return Map.of("success", false, "message", e.getMessage());
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
            // pendinglist
            var pending = stringRedisTemplate.opsForStream()
                    .pending(QUEUE_NAME, "g1", Range.unbounded(), 10000L);
            long pendingCount = pending != null ? pending.size() : 0;
            health.put("stream_pending", pendingCount);
            health.put("status", pendingCount < 1000 ? "HEALTHY" : "WARNING");
        } catch (Exception e) {
            health.put("stream_size", "N/A");
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
        }

        try {
            // 死信队列
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

    /**
     * 清空指定券的 Stream 中的僵尸消息（运维清理用）
     * DELETE /admin/stream/clean?voucherId={id}
     */
    @DeleteMapping("/stream/clean")
    public Map<String, Object> cleanStream(@RequestParam Long voucherId) {
        // 这里只是示意——实际清理 Stream 需要更精细的操作
        // Redis Stream 不支持按字段过滤删除，只能按 messageId 删除
        log.warn("Stream 清理操作执行 voucherId={}", voucherId);
        return Map.of(
                "success", true,
                "message", "Redis Stream 不支持按 voucherId 批量删除，请使用 redis-cli 操作"
        );
    }
}
