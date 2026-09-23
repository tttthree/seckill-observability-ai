package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.constant.ContextConstants;
import com.hmdp.constant.MetricsConstants;
import com.hmdp.constant.RedisConstants;
import com.hmdp.dto.context.IncidentContext;
import com.hmdp.dto.context.IncidentContext.ConsumerGroupSummary;
import com.hmdp.dto.context.IncidentContext.ConsumerHealthEvidence;
import com.hmdp.dto.context.IncidentContext.ContextQuality;
import com.hmdp.dto.context.IncidentContext.DatabaseEvidence;
import com.hmdp.dto.context.IncidentContext.DeadLetterEntry;
import com.hmdp.dto.context.IncidentContext.DeadLetterStream;
import com.hmdp.dto.context.IncidentContext.DirtyVouchers;
import com.hmdp.dto.context.IncidentContext.IncidentEvidence;
import com.hmdp.dto.context.IncidentContext.MetricsEvidence;
import com.hmdp.dto.context.IncidentContext.PreviousIncident;
import com.hmdp.dto.context.IncidentContext.QueueEvidence;
import com.hmdp.dto.context.IncidentContext.RecentOrder;
import com.hmdp.dto.context.IncidentContext.RedisCardinality;
import com.hmdp.dto.context.IncidentContext.RedisEvidence;
import com.hmdp.dto.context.IncidentContext.RedisScalarValue;
import com.hmdp.dto.context.IncidentContext.RuntimeEvidence;
import com.hmdp.dto.context.IncidentContext.SeckillVoucherRow;
import com.hmdp.dto.context.IncidentContext.SourceError;
import com.hmdp.dto.context.IncidentContext.StreamSummary;
import com.hmdp.dto.context.IncidentContext.Truncation;
import com.hmdp.entity.Incident;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.enums.ContextSourceErrorType;
import com.hmdp.enums.IncidentType;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.monitor.ConsumerHealthIndicator;
import com.hmdp.service.IncidentContextBuilder;
import com.hmdp.service.IncidentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * IncidentContext 构建实现。
 *
 * <p>
 * 采集原则：按 IncidentType 严格计划数据源（未计划的数据源既不采集、也不计入 quality）；
 * 单个数据源失败不影响其它数据源；失败只记录 source/error_type/通用描述，原始异常写日志；
 * 所有集合读取都有上限；全程只读。
 * </p>
 */
@Slf4j
@Service
public class IncidentContextBuilderImpl implements IncidentContextBuilder {

    /** 计数器短名 → Redis key，顺序固定以保证 JSON 稳定 */
    private static final Map<String, String> COUNTER_KEYS = buildCounterKeys();

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
        // 主证据：Incident 行。读取异常按 V2-1.1 运维查询语义直接抛出，由 WebExceptionAdvice 显式失败。
        Incident incident = incidentService.getIncident(incidentId);
        if (incident == null) {
            return null;
        }

        BuildState state = new BuildState(incidentId, plannedSources(incident.getIncidentType()));
        state.markAvailable(ContextConstants.SOURCE_INCIDENT);

        IncidentContext context = new IncidentContext()
                .setContextVersion(ContextConstants.CONTEXT_VERSION)
                .setBuiltAt(Instant.now());

        Long voucherId = incident.getRelatedVoucherId();

        context.setIncident(collect(state, ContextConstants.SOURCE_INCIDENT,
                () -> readIncidentEvidence(incident, state), IncidentEvidence::setObservedAt));

        if (state.isPlanned(ContextConstants.SOURCE_METRICS)) {
            context.setMetrics(collect(state, ContextConstants.SOURCE_METRICS,
                    this::readMetrics, MetricsEvidence::setObservedAt));
        }

        if (state.isPlanned(ContextConstants.SOURCE_REDIS)) {
            context.setRedis(collect(state, ContextConstants.SOURCE_REDIS,
                    () -> readRedis(voucherId), RedisEvidence::setObservedAt));
        }

        if (state.isPlanned(ContextConstants.SOURCE_DATABASE)) {
            context.setDatabase(collect(state, ContextConstants.SOURCE_DATABASE,
                    () -> readDatabase(voucherId, state), DatabaseEvidence::setObservedAt));
        }

        if (state.isPlanned(ContextConstants.SOURCE_QUEUE)) {
            context.setQueue(collect(state, ContextConstants.SOURCE_QUEUE,
                    () -> readQueue(incident, state), QueueEvidence::setObservedAt));
        }

        if (state.isPlanned(ContextConstants.SOURCE_CONSUMER_HEALTH)) {
            context.setConsumerHealth(collect(state, ContextConstants.SOURCE_CONSUMER_HEALTH,
                    this::readConsumerHealth, ConsumerHealthEvidence::setObservedAt));
        }

        if (state.isPlanned(ContextConstants.SOURCE_RUNTIME)) {
            context.setRuntime(collect(state, ContextConstants.SOURCE_RUNTIME,
                    this::readRuntime, RuntimeEvidence::setObservedAt));
        }

        return context.setContextQuality(state.toQuality());
    }

    // ==================== 采集计划（严格按 IncidentType） ====================

    /**
     * INVENTORY_MISMATCH   : incident + metrics + voucher Redis + voucher DB
     * DEAD_LETTER          : incident + metrics + voucher Redis + voucher DB + queue + consumer_health
     * CONSUMER_UNHEALTHY   : incident + metrics + queue + consumer_health + runtime
     * 未知类型             : incident + metrics（保守最小集）
     */
    private static List<String> plannedSources(IncidentType type) {
        if (type == null) {
            return List.of(ContextConstants.SOURCE_INCIDENT, ContextConstants.SOURCE_METRICS);
        }
        switch (type) {
            case INVENTORY_MISMATCH:
                return List.of(ContextConstants.SOURCE_INCIDENT, ContextConstants.SOURCE_METRICS,
                        ContextConstants.SOURCE_REDIS, ContextConstants.SOURCE_DATABASE);
            case DEAD_LETTER:
                return List.of(ContextConstants.SOURCE_INCIDENT, ContextConstants.SOURCE_METRICS,
                        ContextConstants.SOURCE_REDIS, ContextConstants.SOURCE_DATABASE,
                        ContextConstants.SOURCE_QUEUE, ContextConstants.SOURCE_CONSUMER_HEALTH);
            case CONSUMER_UNHEALTHY:
                return List.of(ContextConstants.SOURCE_INCIDENT, ContextConstants.SOURCE_METRICS,
                        ContextConstants.SOURCE_QUEUE, ContextConstants.SOURCE_CONSUMER_HEALTH,
                        ContextConstants.SOURCE_RUNTIME);
            default:
                return List.of(ContextConstants.SOURCE_INCIDENT, ContextConstants.SOURCE_METRICS);
        }
    }

    // ==================== incident ====================

    private IncidentEvidence readIncidentEvidence(Incident incident, BuildState state) {
        IncidentEvidence evidence = new IncidentEvidence()
                .setIncidentId(incident.getId())
                .setIncidentType(name(incident.getIncidentType()))
                .setSeverity(name(incident.getSeverity()))
                .setSource(name(incident.getSource()))
                .setStatus(name(incident.getStatus()))
                .setBusinessKey(incident.getBusinessKey())
                .setRelatedVoucherId(incident.getRelatedVoucherId())
                .setOccurrenceCount(incident.getOccurrenceCount())
                // 数据库存储的是无时区 LocalDateTime，原样输出，不附加 offset
                .setFirstDetectedAt(incident.getFirstDetectedAt())
                .setLastDetectedAt(incident.getLastDetectedAt())
                .setResolvedAt(incident.getResolvedAt())
                .setTitle(incident.getTitle())
                .setDescription(incident.getDescription())
                .setDetectedSnapshotScope("LATEST_DETECTION");

        evidence.setDetectedSnapshot(projectSnapshot(incident, state));
        evidence.setRecentPreviousIncidents(readPreviousIncidents(incident, state));
        return evidence;
    }

    /**
     * snapshot 按 IncidentType 白名单投影；解析失败不影响其它字段，只记 PARSE_ERROR。
     */
    private Map<String, Object> projectSnapshot(Incident incident, BuildState state) {
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
            state.markError(ContextConstants.SOURCE_INCIDENT, ContextSourceErrorType.PARSE_ERROR);
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

    /**
     * 历史 Incident 只返回最近 N 条（排除当前 Incident），不做 total/open/resolved 额外统计。
     */
    private List<PreviousIncident> readPreviousIncidents(Incident incident, BuildState state) {
        int limit = ContextConstants.RECENT_PREVIOUS_INCIDENTS_LIMIT;
        try {
            List<Incident> candidates = incidentService.listIncidents(null, incident.getIncidentType(),
                    incident.getRelatedVoucherId(), limit + 1);
            List<PreviousIncident> previous = new ArrayList<>();
            for (Incident candidate : candidates) {
                if (candidate.getId() != null && candidate.getId().equals(incident.getId())) {
                    continue;
                }
                if (previous.size() >= limit) {
                    state.markTruncation("recent_previous_incidents", limit, previous.size());
                    break;
                }
                previous.add(new PreviousIncident()
                        .setIncidentId(candidate.getId())
                        .setStatus(name(candidate.getStatus()))
                        .setSeverity(name(candidate.getSeverity()))
                        .setOccurrenceCount(candidate.getOccurrenceCount())
                        .setFirstDetectedAt(candidate.getFirstDetectedAt())
                        .setLastDetectedAt(candidate.getLastDetectedAt())
                        .setResolvedAt(candidate.getResolvedAt()));
            }
            return previous;
        } catch (Exception e) {
            log.warn("历史 Incident 读取失败 incidentId={}", incident.getId(), e);
            state.markError(ContextConstants.SOURCE_INCIDENT, ContextSourceErrorType.DATABASE_ERROR);
            return null;
        }
    }

    // ==================== metrics ====================

    /**
     * 只投影真实运行计数器与 counter_presence：不含 benchmark context / load_model /
     * expected_model / comparison，也不重复 consumer health 字段。
     */
    private MetricsEvidence readMetrics() {
        List<String> keys = new ArrayList<>(COUNTER_KEYS.values());
        List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);

        Map<String, Double> counters = new LinkedHashMap<>();
        Map<String, Boolean> presence = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, String> entry : COUNTER_KEYS.entrySet()) {
            String raw = values == null ? null : values.get(index++);
            boolean present = StringUtils.hasText(raw);
            presence.put(entry.getKey(), present);
            // key 不存在时按项目既有约定取 0，同时对下游显式暴露 counter_presence=false
            counters.put(entry.getKey(), present ? parseDoubleOrNull(raw) : Double.valueOf(0.0d));
        }
        return new MetricsEvidence().setCounters(counters).setCounterPresence(presence);
    }

    private static Map<String, String> buildCounterKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("total_requests", MetricsConstants.M_TOTAL_REQUESTS);
        keys.put("reserve_success", MetricsConstants.M_RESERVE_SUCCESS);
        keys.put("reserve_error", MetricsConstants.M_RESERVE_ERROR);
        keys.put("duplicate_request", MetricsConstants.M_DUPLICATE_REQUEST);
        keys.put("commit_success", MetricsConstants.M_COMMIT_SUCCESS);
        keys.put("commit_error", MetricsConstants.M_COMMIT_ERROR);
        keys.put("stock_fail_redis", MetricsConstants.M_STOCK_FAIL_REDIS);
        keys.put("stock_fail_db", MetricsConstants.M_STOCK_FAIL_DB);
        keys.put("consume_error", MetricsConstants.M_CONSUME_ERROR);
        keys.put("reconcile_mismatch", MetricsConstants.M_RECONCILE_MISMATCH);
        return Collections.unmodifiableMap(keys);
    }

    // ==================== redis（券维度） ====================

    private RedisEvidence readRedis(Long voucherId) {
        RedisEvidence evidence = new RedisEvidence();
        if (voucherId == null) {
            // 无券维度时不做任何臆造，各子段保持 null
            return evidence;
        }

        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        String stockRaw = stringRedisTemplate.opsForValue().get(stockKey);
        evidence.setVoucherStock(new RedisScalarValue()
                .setKey(stockKey)
                .setPresent(stockRaw != null)
                .setValue(stockRaw == null ? null : parseLongOrNull(stockRaw)));

        String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
        boolean orderPresent = Boolean.TRUE.equals(stringRedisTemplate.hasKey(orderKey));
        evidence.setVoucherOrderedUsers(new RedisCardinality()
                .setKey(orderKey)
                .setPresent(orderPresent)
                .setCardinality(orderPresent ? stringRedisTemplate.opsForSet().size(orderKey) : null));

        String dirtyKey = RedisConstants.SECKILL_VOUCHER_DIRTY_KEY;
        boolean dirtyPresent = Boolean.TRUE.equals(stringRedisTemplate.hasKey(dirtyKey));
        evidence.setDirtyVouchers(new DirtyVouchers()
                .setKey(dirtyKey)
                .setPresent(dirtyPresent)
                .setMemberCount(dirtyPresent ? stringRedisTemplate.opsForSet().size(dirtyKey) : null)
                // 成员判定对不存在的 key 语义明确（false 即"不在集合中"）
                .setContainsVoucher(Boolean.TRUE.equals(
                        stringRedisTemplate.opsForSet().isMember(dirtyKey, String.valueOf(voucherId)))));

        String markerKey = RedisConstants.SECKILL_RECONCILE_MISMATCH_KEY + voucherId;
        String markerRaw = stringRedisTemplate.opsForValue().get(markerKey);
        evidence.setReconcileMismatchMarker(new RedisScalarValue()
                .setKey(markerKey)
                .setPresent(markerRaw != null)
                .setValue(markerRaw == null ? null : parseLongOrNull(markerRaw)));

        return evidence;
    }

    // ==================== database ====================

    private DatabaseEvidence readDatabase(Long voucherId, BuildState state) {
        DatabaseEvidence evidence = new DatabaseEvidence()
                .setRecentOrdersLimit(ContextConstants.RECENT_ORDERS_LIMIT);
        if (voucherId == null) {
            return evidence;
        }

        SeckillVoucher voucher = seckillVoucherMapper.selectById(voucherId);
        if (voucher != null) {
            evidence.setSeckillVoucher(new SeckillVoucherRow()
                    .setVoucherId(voucher.getVoucherId())
                    .setStock(voucher.getStock())
                    .setBeginTime(voucher.getBeginTime())
                    .setEndTime(voucher.getEndTime())
                    .setUpdateTime(voucher.getUpdateTime()));
        }

        Integer orderCount = voucherOrderMapper.selectCount(
                Wrappers.<VoucherOrder>lambdaQuery().eq(VoucherOrder::getVoucherId, voucherId));
        evidence.setOrderCountForVoucher(orderCount == null ? null : orderCount.longValue());

        List<VoucherOrder> orders = voucherOrderMapper.selectList(Wrappers.<VoucherOrder>lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .orderByDesc(VoucherOrder::getCreateTime)
                .last("LIMIT " + ContextConstants.RECENT_ORDERS_LIMIT));

        List<RecentOrder> recentOrders = new ArrayList<>();
        if (orders != null) {
            if (orders.size() >= ContextConstants.RECENT_ORDERS_LIMIT) {
                state.markTruncation("recent_orders", ContextConstants.RECENT_ORDERS_LIMIT, orders.size());
            }
            for (VoucherOrder order : orders) {
                // 刻意不输出 user_id
                recentOrders.add(new RecentOrder()
                        .setOrderId(order.getId())
                        .setVoucherId(order.getVoucherId())
                        .setCreateTime(order.getCreateTime()));
            }
        }
        evidence.setRecentOrders(recentOrders);
        return evidence;
    }

    // ==================== queue ====================

    private QueueEvidence readQueue(Incident incident, BuildState state) {
        QueueEvidence evidence = new QueueEvidence();

        String streamKey = RedisConstants.STREAM_ORDERS_KEY;
        boolean streamExists = Boolean.TRUE.equals(stringRedisTemplate.hasKey(streamKey));
        StreamSummary summary = new StreamSummary().setKey(streamKey).setExists(streamExists);
        if (streamExists) {
            summary.setLength(stringRedisTemplate.opsForStream().size(streamKey));
            StreamInfo.XInfoStream info = stringRedisTemplate.opsForStream().info(streamKey);
            if (info != null) {
                summary.setFirstEntryId(info.firstEntryId())
                        .setLastEntryId(info.lastEntryId())
                        .setLastGeneratedId(info.lastGeneratedId())
                        .setGroupCount(info.groupCount());
            }
            evidence.setConsumerGroup(readConsumerGroup(streamKey));
        }
        evidence.setMainStream(summary);

        evidence.setDeadLetterStream(readDeadLetterStream());

        if (incident.getIncidentType() == IncidentType.DEAD_LETTER) {
            evidence.setDeadLetterEntriesForVoucher(
                    readDeadLetterEntries(incident, state, evidence.getDeadLetterStream()));
        }
        return evidence;
    }

    /**
     * 只输出消费者组聚合信息，不输出消费者明细列表。
     * <p>
     * 契约刻意不提供 lag / entries_read：当前客户端栈无法稳定获取
     * （spring-data-redis 2.7.18 的 {@link StreamInfo.XInfoGroup} 未暴露这两个字段，
     * 而 {@code RedisConnection.execute} 在 Lettuce 下使用 ByteArrayOutput，
     * 无法解码含整数的嵌套数组回复），因此不把永久为 null 的字段冻结进下游契约。
     */
    private ConsumerGroupSummary readConsumerGroup(String streamKey) {
        String groupName = RedisConstants.STREAM_ORDERS_GROUP;
        ConsumerGroupSummary summary = new ConsumerGroupSummary().setName(groupName);
        try {
            StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream().groups(streamKey);
            if (groups != null) {
                // XInfoGroups 提供 iterator() 但未实现 Iterable，按索引遍历
                for (int i = 0; i < groups.size(); i++) {
                    StreamInfo.XInfoGroup group = groups.get(i);
                    if (groupName.equals(group.groupName())) {
                        summary.setConsumersTotal(group.consumerCount())
                                .setPendingTotal(group.pendingCount())
                                .setLastDeliveredId(group.lastDeliveredId());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("消费者组信息读取失败 key={}", streamKey, e);
        }
        return summary;
    }

    private DeadLetterStream readDeadLetterStream() {
        String deadKey = RedisConstants.STREAM_ORDERS_DEAD_KEY;
        boolean exists = Boolean.TRUE.equals(stringRedisTemplate.hasKey(deadKey));
        return new DeadLetterStream()
                .setKey(deadKey)
                .setExists(exists)
                .setLength(exists ? stringRedisTemplate.opsForStream().size(deadKey) : 0L)
                .setScannedFrom("NEWEST")
                .setScannedLimit(ContextConstants.DEAD_LETTER_SCAN_LIMIT)
                .setEntriesFoundForVoucher(0);
    }

    /**
     * 从最新端读取最近 N 条死信，再按 voucher/order 过滤；达到扫描上限记录 truncation。
     */
    private List<DeadLetterEntry> readDeadLetterEntries(Incident incident, BuildState state,
                                                        DeadLetterStream deadLetterStream) {
        if (deadLetterStream == null || !Boolean.TRUE.equals(deadLetterStream.getExists())) {
            return Collections.emptyList();
        }

        Long targetVoucherId = incident.getRelatedVoucherId();
        Long targetOrderId = snapshotOrderId(incident);
        String deadKey = RedisConstants.STREAM_ORDERS_DEAD_KEY;

        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream()
                .reverseRange(deadKey, Range.unbounded(), Limit.limit().count(ContextConstants.DEAD_LETTER_SCAN_LIMIT));

        List<DeadLetterEntry> entries = new ArrayList<>();
        if (records == null) {
            return entries;
        }
        if (records.size() >= ContextConstants.DEAD_LETTER_SCAN_LIMIT) {
            state.markTruncation("dead_letter_scan", ContextConstants.DEAD_LETTER_SCAN_LIMIT, records.size());
        }
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> value = record.getValue();
            Long voucherId = asLong(value.get("voucherId"));
            Long orderId = asLong(value.get("id"));
            if (targetVoucherId != null && !targetVoucherId.equals(voucherId)) {
                continue;
            }
            if (targetOrderId != null && !targetOrderId.equals(orderId)) {
                continue;
            }
            entries.add(new DeadLetterEntry()
                    .setStreamEntryId(record.getId().getValue())
                    .setOriginalMessageId(asString(value.get("originalMessageId")))
                    .setVoucherId(voucherId)
                    .setOrderId(orderId)
                    .setFailureReason(asString(value.get("failureReason"))));
        }
        deadLetterStream.setEntriesFoundForVoucher(entries.size());
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

    private ConsumerHealthEvidence readConsumerHealth() {
        Health health = consumerHealthIndicator.health();
        Map<String, Object> details = health.getDetails();
        return new ConsumerHealthEvidence()
                .setStatus(health.getStatus().getCode())
                .setConsumerStatus(asString(details.get("consumer_status")))
                .setConsumerAlive(asBoolean(details.get("consumer_alive")))
                .setHeartbeatAgeMs(asLong(details.get("heartbeat_age_ms")))
                .setSuccessHeartbeatAgeMs(asLong(details.get("success_heartbeat_age_ms")))
                .setPendingCount(asLong(details.get("pending_count")))
                // 只取 reason 文本，刻意不暴露 redis_error 等原始异常明细
                .setReason(asString(details.get("reason")));
    }

    // ==================== runtime ====================

    private RuntimeEvidence readRuntime() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        return new RuntimeEvidence()
                .setUptimeMs(ManagementFactory.getRuntimeMXBean().getUptime())
                .setHeapUsedBytes(memory.getHeapMemoryUsage().getUsed())
                .setThreadCount(ManagementFactory.getThreadMXBean().getThreadCount())
                .setAvailableProcessors(Runtime.getRuntime().availableProcessors())
                .setJavaVersion(System.getProperty("java.version"));
    }

    // ==================== 采集包装与质量记录 ====================

    private <T> T collect(BuildState state, String source, Supplier<T> reader, BiConsumer<T, Instant> stampObservedAt) {
        try {
            T value = reader.get();
            Instant observedAt = Instant.now();
            stampObservedAt.accept(value, observedAt);
            state.markAvailable(source);
            return value;
        } catch (Exception e) {
            // 原始异常只进服务日志，不进契约
            log.warn("IncidentContext 数据源读取失败 source={}, incidentId={}", source, state.incidentId, e);
            state.markUnavailable(source, classify(source, e));
            return null;
        }
    }

    private static ContextSourceErrorType classify(String source, Exception e) {
        boolean connectionIssue = isConnectionIssue(e);
        if (ContextConstants.SOURCE_REDIS.equals(source)
                || ContextConstants.SOURCE_QUEUE.equals(source)
                || ContextConstants.SOURCE_METRICS.equals(source)) {
            return connectionIssue ? ContextSourceErrorType.REDIS_UNAVAILABLE : ContextSourceErrorType.REDIS_ERROR;
        }
        if (ContextConstants.SOURCE_DATABASE.equals(source) || ContextConstants.SOURCE_INCIDENT.equals(source)) {
            return connectionIssue ? ContextSourceErrorType.DATABASE_UNAVAILABLE : ContextSourceErrorType.DATABASE_ERROR;
        }
        if (ContextConstants.SOURCE_CONSUMER_HEALTH.equals(source)) {
            return ContextSourceErrorType.HEALTH_ERROR;
        }
        return ContextSourceErrorType.RUNTIME_ERROR;
    }

    private static boolean isConnectionIssue(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String name = current.getClass().getSimpleName();
            if (name.contains("Connection") || name.contains("Timeout") || name.contains("Unavailable")) {
                return true;
            }
        }
        return false;
    }

    /** 单次构建的可变状态：planned 决定采集范围，未计划的数据源不会进入任何列表 */
    private static final class BuildState {

        private final long incidentId;
        private final List<String> planned;
        private final List<String> available = new ArrayList<>();
        private final List<String> unavailable = new ArrayList<>();
        private final List<SourceError> errors = new ArrayList<>();
        private final List<Truncation> truncations = new ArrayList<>();

        private BuildState(long incidentId, List<String> planned) {
            this.incidentId = incidentId;
            this.planned = new ArrayList<>(planned);
        }

        private boolean isPlanned(String source) {
            return planned.contains(source);
        }

        private void markAvailable(String source) {
            if (!available.contains(source)) {
                available.add(source);
            }
        }

        private void markUnavailable(String source, ContextSourceErrorType errorType) {
            if (!unavailable.contains(source)) {
                unavailable.add(source);
            }
            markError(source, errorType);
        }

        private void markError(String source, ContextSourceErrorType errorType) {
            errors.add(new SourceError()
                    .setSource(source)
                    .setErrorType(errorType.name())
                    .setMessage(errorType.getSafeMessage()));
        }

        private void markTruncation(String source, int limit, int returned) {
            truncations.add(new Truncation()
                    .setSource(source)
                    .setLimit(limit)
                    .setReturned(returned)
                    .setTruncated(true));
        }

        private ContextQuality toQuality() {
            List<String> availableOrdered = new ArrayList<>();
            List<String> unavailableOrdered = new ArrayList<>();
            for (String source : planned) {
                if (available.contains(source)) {
                    availableOrdered.add(source);
                } else if (unavailable.contains(source)) {
                    unavailableOrdered.add(source);
                }
            }
            return new ContextQuality()
                    .setComplete(unavailableOrdered.isEmpty() && errors.isEmpty())
                    .setPlannedSources(new ArrayList<>(planned))
                    .setAvailableSources(availableOrdered)
                    .setUnavailableSources(unavailableOrdered)
                    .setNotImplementedSources(List.of(ContextConstants.SOURCE_LOGS))
                    .setErrors(errors)
                    .setTruncations(truncations)
                    .setNotes(notes());
        }

        private static List<String> notes() {
            return List.of(
                    ContextConstants.NOTE_TIME_SEMANTICS,
                    ContextConstants.NOTE_EVIDENCE_VS_STATE,
                    ContextConstants.NOTE_SNAPSHOT_SCOPE,
                    ContextConstants.NOTE_METRICS_CONVENTION,
                    ContextConstants.NOTE_PRIVACY,
                    ContextConstants.NOTE_GROUP_LAG_NOT_COLLECTED,
                    ContextConstants.NOTE_LOGS);
        }
    }

    // ==================== 类型转换工具 ====================

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static Double parseDoubleOrNull(String raw) {
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
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

    private static Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.valueOf(String.valueOf(value));
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
