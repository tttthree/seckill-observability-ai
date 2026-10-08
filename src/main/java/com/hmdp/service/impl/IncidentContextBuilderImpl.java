package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.constant.ContextConstants;
import com.hmdp.constant.RedisConstants;
import com.hmdp.dto.context.IncidentContext;
import com.hmdp.dto.context.IncidentContext.ConsumerHealthEvidence;
import com.hmdp.dto.context.IncidentContext.DatabaseEvidence;
import com.hmdp.dto.context.IncidentContext.DeadLetterEntry;
import com.hmdp.dto.context.IncidentContext.IncidentEvidence;
import com.hmdp.dto.context.IncidentContext.QueueEvidence;
import com.hmdp.dto.context.IncidentContext.RecentOrder;
import com.hmdp.dto.context.IncidentContext.RedisEvidence;
import com.hmdp.dto.context.IncidentContext.StockState;
import com.hmdp.entity.Incident;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.enums.IncidentType;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.monitor.ConsumerHealthIndicator;
import com.hmdp.service.IncidentContextBuilder;
import com.hmdp.service.IncidentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * IncidentContext 构建实现（契约 v3.0）。
 *
 * <p>
 * 采集原则：按 IncidentType 计划最小必要数据源；单个数据源失败不影响其它数据源，
 * 失败只把该段置 null 并把数据源名记入 {@code unavailable_sources}，
 * 原始异常只写服务日志（不进契约）；所有集合读取都有内部上限；全程只读。
 * </p>
 */
@Slf4j
@Service
public class IncidentContextBuilderImpl implements IncidentContextBuilder {

    @Resource
    private IncidentService incidentService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private ConsumerHealthIndicator consumerHealthIndicator;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public IncidentContext build(long incidentId) {
        // 主证据：Incident 行。读取异常按运维查询语义直接抛出，由 WebExceptionAdvice 显式失败，
        // 不降级为 unavailable_sources（Incident 不存在时返回 null，由 Controller 转 404）。
        Incident incident = incidentService.getIncident(incidentId);
        if (incident == null) {
            return null;
        }

        BuildState state = new BuildState(incidentId, plannedSources(incident.getIncidentType()));

        IncidentContext context = new IncidentContext()
                .setContextVersion(ContextConstants.CONTEXT_VERSION)
                .setBuiltAt(Instant.now());

        Long voucherId = incident.getRelatedVoucherId();

        context.setIncident(collect(state, ContextConstants.SOURCE_INCIDENT,
                () -> readIncidentEvidence(incident)));

        if (state.isPlanned(ContextConstants.SOURCE_REDIS)) {
            context.setRedis(collect(state, ContextConstants.SOURCE_REDIS,
                    () -> readRedis(voucherId)));
        }

        if (state.isPlanned(ContextConstants.SOURCE_DATABASE)) {
            context.setDatabase(collect(state, ContextConstants.SOURCE_DATABASE,
                    () -> readDatabase(voucherId)));
        }

        if (state.isPlanned(ContextConstants.SOURCE_QUEUE)) {
            context.setQueue(collect(state, ContextConstants.SOURCE_QUEUE,
                    () -> readQueue(incident)));
        }

        if (state.isPlanned(ContextConstants.SOURCE_CONSUMER_HEALTH)) {
            context.setConsumerHealth(collect(state, ContextConstants.SOURCE_CONSUMER_HEALTH,
                    this::readConsumerHealth));
        }

        return context.setUnavailableSources(state.unavailableSources());
    }

    // ==================== 采集计划（仅 Java 内部策略，不下发） ====================

    /**
     * INVENTORY_MISMATCH  : incident + redis + database
     * DEAD_LETTER         : incident + redis + database + queue + consumer_health
     * CONSUMER_UNHEALTHY  : incident + consumer_health
     * 未知类型            : incident（保守最小集）
     */
    private static List<String> plannedSources(IncidentType type) {
        if (type == null) {
            return List.of(ContextConstants.SOURCE_INCIDENT);
        }
        switch (type) {
            case INVENTORY_MISMATCH:
                return List.of(ContextConstants.SOURCE_INCIDENT,
                        ContextConstants.SOURCE_REDIS, ContextConstants.SOURCE_DATABASE);
            case DEAD_LETTER:
                return List.of(ContextConstants.SOURCE_INCIDENT,
                        ContextConstants.SOURCE_REDIS, ContextConstants.SOURCE_DATABASE,
                        ContextConstants.SOURCE_QUEUE, ContextConstants.SOURCE_CONSUMER_HEALTH);
            case CONSUMER_UNHEALTHY:
                return List.of(ContextConstants.SOURCE_INCIDENT,
                        ContextConstants.SOURCE_CONSUMER_HEALTH);
            default:
                return List.of(ContextConstants.SOURCE_INCIDENT);
        }
    }

    // ==================== incident ====================

    /** 包内可见：仅供单测以 spy 方式验证"incident 段失败"路径 */
    IncidentEvidence readIncidentEvidence(Incident incident) {
        return new IncidentEvidence()
                .setIncidentId(incident.getId())
                .setIncidentType(name(incident.getIncidentType()))
                .setSeverity(name(incident.getSeverity()))
                .setStatus(name(incident.getStatus()))
                .setRelatedVoucherId(incident.getRelatedVoucherId())
                .setOccurrenceCount(incident.getOccurrenceCount())
                // 数据库存储的是无时区 LocalDateTime，原样输出，不附加 offset
                .setFirstDetectedAt(incident.getFirstDetectedAt())
                .setLastDetectedAt(incident.getLastDetectedAt())
                .setResolvedAt(incident.getResolvedAt())
                .setTitle(incident.getTitle())
                .setDescription(incident.getDescription())
                .setDetectedSnapshot(projectSnapshot(incident));
    }

    /**
     * snapshot 按 IncidentType 白名单投影；解析失败不影响其它字段，只记日志并返回 null。
     */
    private Map<String, Object> projectSnapshot(Incident incident) {
        String raw = incident.getSnapshot();
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        List<String> allowList = snapshotAllowList(incident.getIncidentType());
        if (allowList.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
            Map<String, Object> projected = new LinkedHashMap<>();
            for (String field : allowList) {
                if (parsed.containsKey(field)) {
                    projected.put(field, parsed.get(field));
                }
            }
            return projected.isEmpty() ? null : projected;
        } catch (Exception e) {
            log.warn("Incident snapshot 解析失败 incidentId={}", incident.getId(), e);
            return null;
        }
    }

    private static List<String> snapshotAllowList(IncidentType type) {
        if (type == null) {
            return Collections.emptyList();
        }
        switch (type) {
            case INVENTORY_MISMATCH:
                return ContextConstants.SNAPSHOT_FIELDS_INVENTORY_MISMATCH;
            case DEAD_LETTER:
                return ContextConstants.SNAPSHOT_FIELDS_DEAD_LETTER;
            case CONSUMER_UNHEALTHY:
                return ContextConstants.SNAPSHOT_FIELDS_CONSUMER_UNHEALTHY;
            default:
                return Collections.emptyList();
        }
    }

    // ==================== redis（券维度） ====================

    private RedisEvidence readRedis(Long voucherId) {
        RedisEvidence evidence = new RedisEvidence();
        if (voucherId == null) {
            // 无券维度时不做任何臆造，各子段保持 null
            return evidence;
        }

        String stockRaw = stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        evidence.setStock(new StockState()
                .setPresent(stockRaw != null)
                // 真实缺失必须是 null，绝不当 0
                .setValue(stockRaw == null ? null : parseLongOrNull(stockRaw)));

        String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
        // Set 不存在时 SCARD 语义就是 0（正常的空集合），不是"读取失败"；
        // 真正读取失败会由外层 collect 把整个 redis 段置 null 并记入 unavailable_sources。
        Long orderedUserCount = stringRedisTemplate.opsForSet().size(orderKey);
        evidence.setOrderedUserCount(orderedUserCount == null ? 0L : orderedUserCount);

        evidence.setDirty(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(RedisConstants.SECKILL_VOUCHER_DIRTY_KEY, String.valueOf(voucherId))));

        evidence.setMismatchPending(stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_RECONCILE_MISMATCH_KEY + voucherId) != null);

        return evidence;
    }

    // ==================== database ====================

    private DatabaseEvidence readDatabase(Long voucherId) {
        DatabaseEvidence evidence = new DatabaseEvidence();
        if (voucherId == null) {
            evidence.setVoucherExists(false);
            evidence.setRecentOrders(Collections.emptyList());
            return evidence;
        }

        SeckillVoucher voucher = seckillVoucherMapper.selectById(voucherId);
        evidence.setVoucherExists(voucher != null);
        // 券不存在时 stock 保持 null，不得写成 0
        evidence.setStock(voucher == null ? null : voucher.getStock());

        Integer orderCount = voucherOrderMapper.selectCount(
                Wrappers.<VoucherOrder>lambdaQuery().eq(VoucherOrder::getVoucherId, voucherId));
        evidence.setOrderCount(orderCount == null ? null : orderCount.longValue());

        // 只取最近 N 条；内部上限不下发为契约字段
        List<VoucherOrder> orders = voucherOrderMapper.selectList(Wrappers.<VoucherOrder>lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .orderByDesc(VoucherOrder::getCreateTime)
                .last("LIMIT " + ContextConstants.RECENT_ORDERS_LIMIT));

        List<RecentOrder> recentOrders = new ArrayList<>();
        if (orders != null) {
            for (VoucherOrder order : orders) {
                // 刻意不输出 user_id 与 voucher_id（voucher 维度已由 related_voucher_id 确定）
                recentOrders.add(new RecentOrder()
                        .setOrderId(order.getId())
                        .setCreateTime(order.getCreateTime()));
            }
        }
        evidence.setRecentOrders(recentOrders);
        return evidence;
    }

    // ==================== queue ====================

    private QueueEvidence readQueue(Incident incident) {
        QueueEvidence evidence = new QueueEvidence();

        // PEL 中未 ACK 的消息数（XPENDING 汇总）
        PendingMessagesSummary pending = stringRedisTemplate.opsForStream()
                .pending(RedisConstants.STREAM_ORDERS_KEY, RedisConstants.STREAM_ORDERS_GROUP);
        evidence.setPendingCount(pending == null ? 0L : pending.getTotalPendingMessages());

        // 整个死信流的 XLEN 只在内部用于"为空则跳过扫描"的有界读取优化，
        // 不下发给 AI：global DLQ count > 0 不代表当前 voucher 有死信。
        Long deadStreamLength = stringRedisTemplate.opsForStream()
                .size(RedisConstants.STREAM_ORDERS_DEAD_KEY);
        evidence.setDeadLetters(readDeadLetters(incident, deadStreamLength));
        return evidence;
    }

    /**
     * 从最新端有界读取最近 {@code DEAD_LETTER_SCAN_LIMIT} 条死信，再按券过滤。
     * <p>
     * 过滤语义与券级 Incident 聚合保持一致：只要 Incident 有 relatedVoucherId，就只按 voucherId
     * 匹配（同券的多个不同 orderId 都属于同一故障事件）；snapshot 中的 order_id 仅代表
     * "最近一次检测证据"，只在 relatedVoucherId 缺失时作为 fallback 使用。
     * <p>
     * 达到扫描上限只写日志，不进入契约（不引入 truncation / scan_limit / notes）。
     *
     * @param deadStreamLength 整个死信流的 XLEN，仅用于"为空则跳过扫描"的内部优化，不下发
     */
    private List<DeadLetterEntry> readDeadLetters(Incident incident, Long deadStreamLength) {
        if (deadStreamLength == null || deadStreamLength == 0L) {
            return Collections.emptyList();
        }

        String deadKey = RedisConstants.STREAM_ORDERS_DEAD_KEY;
        int scanLimit = ContextConstants.DEAD_LETTER_SCAN_LIMIT;

        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream()
                .reverseRange(deadKey, Range.unbounded(), Limit.limit().count(scanLimit));
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        if (records.size() >= scanLimit) {
            log.warn("死信流扫描达到内部上限 {}，本轮只输出已读到的条目", scanLimit);
        }

        Long targetVoucherId = incident.getRelatedVoucherId();
        Long fallbackOrderId = targetVoucherId == null ? snapshotOrderId(incident) : null;

        List<DeadLetterEntry> entries = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> value = record.getValue();
            Long voucherId = asLong(value.get("voucherId"));
            Long orderId = asLong(value.get("id"));
            if (targetVoucherId != null) {
                if (!targetVoucherId.equals(voucherId)) {
                    continue;
                }
            } else if (fallbackOrderId != null && !fallbackOrderId.equals(orderId)) {
                continue;
            }
            entries.add(new DeadLetterEntry()
                    // 统一语义：message_id 即原主 Stream 的消息 id
                    .setMessageId(asString(value.get("originalMessageId")))
                    .setOrderId(orderId)
                    .setFailureReason(asString(value.get("failureReason"))));
        }
        return entries;
    }

    private Long snapshotOrderId(Incident incident) {
        String raw = incident.getSnapshot();
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
            Object orderId = parsed.get("order_id");
            return orderId == null ? null : asLong(orderId);
        } catch (Exception e) {
            log.debug("snapshot order_id 解析失败 incidentId={}", incident.getId(), e);
            return null;
        }
    }

    // ==================== consumer health ====================

    /**
     * 把 Actuator 的 UP/DOWN 与内部 consumer_status 投影成单一 status。
     * <p>
     * 只做投影，不复制 {@link ConsumerHealthIndicator} 的阈值判定。
     */
    private ConsumerHealthEvidence readConsumerHealth() {
        Health health = consumerHealthIndicator.health();
        Map<String, Object> details = health.getDetails();

        boolean up = Status.UP.equals(health.getStatus());
        String consumerStatus = up
                ? String.valueOf(details.getOrDefault("consumer_status", "HEALTHY"))
                : "DOWN";
        String status = up
                ? ("DEGRADED".equals(consumerStatus) ? "DEGRADED" : "HEALTHY")
                : "DOWN";

        return new ConsumerHealthEvidence()
                .setStatus(status)
                .setHeartbeatAgeMs(asLong(details.get("heartbeat_age_ms")))
                .setSuccessHeartbeatAgeMs(asLong(details.get("success_heartbeat_age_ms")))
                .setPendingCount(asLong(details.get("pending_count")))
                // 只取 reason 文本，刻意不暴露 redis_error 等原始异常明细
                .setReason(asString(details.get("reason")));
    }

    // ==================== 采集包装 ====================

    /**
     * 单数据源采集：成功返回结果，失败只记日志、把数据源名记入 unavailable，并返回 null。
     * 原始异常绝不进入契约。
     */
    private <T> T collect(BuildState state, String source, Supplier<T> reader) {
        try {
            return reader.get();
        } catch (Exception e) {
            log.warn("IncidentContext 数据源读取失败 source={}, incidentId={}", source, state.incidentId, e);
            state.markUnavailable(source);
            return null;
        }
    }

    /** 单次构建的可变状态。包内可见以支持单测。 */
    static final class BuildState {

        private final long incidentId;
        private final List<String> planned;
        private final List<String> unavailable = new ArrayList<>();

        BuildState(long incidentId, List<String> planned) {
            this.incidentId = incidentId;
            this.planned = new ArrayList<>(planned);
        }

        boolean isPlanned(String source) {
            return planned.contains(source);
        }

        void markUnavailable(String source) {
            if (!unavailable.contains(source)) {
                unavailable.add(source);
            }
        }

        /** 只输出本应采集却失败的数据源，顺序与采集计划一致 */
        List<String> unavailableSources() {
            List<String> ordered = new ArrayList<>();
            for (String source : planned) {
                if (unavailable.contains(source)) {
                    ordered.add(source);
                }
            }
            return ordered;
        }
    }

    // ==================== 类型转换工具 ====================

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Long parseLongOrNull(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof byte[]) {
            // 原始命令（RedisCallback）返回的数值也可能是 byte[]
            return parseLongOrNull(new String((byte[]) value, StandardCharsets.UTF_8));
        }
        return parseLongOrNull(String.valueOf(value));
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }
}
