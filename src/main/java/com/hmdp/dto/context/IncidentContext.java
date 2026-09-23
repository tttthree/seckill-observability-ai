package com.hmdp.dto.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * V2-2 IncidentContext：围绕单个 Incident 的真实运行证据集合，作为 Java→Python 的稳定输入契约。
 *
 * <p>
 * 契约约束（冻结）：
 * </p>
 * <ul>
 *   <li>只放真实读取到的数据，不做任何 root cause 推断；</li>
 *   <li>每个嵌套段都声明 {@code @JsonInclude(ALWAYS)}，保证 nullable 字段真实序列化为 null；</li>
 *   <li>built_at / observed_at 使用 UTC {@link Instant}；Incident 的 {@link LocalDateTime} 原样输出（历史存储无时区）；</li>
 *   <li>未计划的数据源段为 null，且不出现在任何 quality 列表中；</li>
 *   <li>只有 planned 数据源才会出现在 planned/available/unavailable 列表中。</li>
 * </ul>
 */
@Data
@Accessors(chain = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public class IncidentContext {

    private String contextVersion;

    /** 本次 Context 构建时刻（UTC） */
    private Instant builtAt;

    /** 未计划或不可用时为 null */
    private IncidentEvidence incident;
    private MetricsEvidence metrics;
    private RedisEvidence redis;
    private DatabaseEvidence database;
    private QueueEvidence queue;
    private ConsumerHealthEvidence consumerHealth;
    private RuntimeEvidence runtime;

    private ContextQuality contextQuality;

    // ==================== incident ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class IncidentEvidence {

        /** 该段数据读取完成时刻（UTC） */
        private Instant observedAt;

        private Long incidentId;
        private String incidentType;
        private String severity;
        private String source;
        private String status;
        private String businessKey;
        private Long relatedVoucherId;
        private Integer occurrenceCount;

        /** 数据库存的是无时区的 LocalDateTime，原样输出 */
        private LocalDateTime firstDetectedAt;
        private LocalDateTime lastDetectedAt;
        private LocalDateTime resolvedAt;

        private String title;
        private String description;

        /** 按 IncidentType 白名单投影后的检测证据；解析失败或无证据时为 null */
        private Map<String, Object> detectedSnapshot;

        /** 固定为 LATEST_DETECTION：该快照只代表最近一次检测 */
        private String detectedSnapshotScope;

        /** 同一业务键下更早的 Incident（不含当前 Incident）；读取失败时为 null */
        private List<PreviousIncident> recentPreviousIncidents;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class PreviousIncident {
        private Long incidentId;
        private String status;
        private String severity;
        private Integer occurrenceCount;
        private LocalDateTime firstDetectedAt;
        private LocalDateTime lastDetectedAt;
        private LocalDateTime resolvedAt;
    }

    // ==================== metrics（只投影真实运行计数器） ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class MetricsEvidence {

        private Instant observedAt;

        /** 10 个真实运行计数器（key 不存在时为 0.0，配合 counter_presence 判读） */
        private Map<String, Double> counters;

        /** 计数器 key 是否真实存在 */
        private Map<String, Boolean> counterPresence;
    }

    // ==================== redis（券维度） ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class RedisEvidence {

        private Instant observedAt;

        private RedisScalarValue voucherStock;
        private RedisCardinality voucherOrderedUsers;
        private DirtyVouchers dirtyVouchers;
        private RedisScalarValue reconcileMismatchMarker;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class RedisScalarValue {
        private String key;
        /** key 是否存在：false 时 value 必须为 null，不得写成 0 */
        private Boolean present;
        private Long value;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class RedisCardinality {
        private String key;
        private Boolean present;
        private Long cardinality;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class DirtyVouchers {
        private String key;
        private Boolean present;
        /** key 不存在时为 null（不写 0） */
        private Long memberCount;
        /** 成员判定对不存在的 key 语义明确，恒为布尔 */
        private Boolean containsVoucher;
    }

    // ==================== database ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class DatabaseEvidence {

        private Instant observedAt;

        /** 券不存在时为 null */
        private SeckillVoucherRow seckillVoucher;
        private Long orderCountForVoucher;
        /** 不含 user_id */
        private List<RecentOrder> recentOrders;
        private Integer recentOrdersLimit;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class SeckillVoucherRow {
        private Long voucherId;
        private Integer stock;
        private LocalDateTime beginTime;
        private LocalDateTime endTime;
        private LocalDateTime updateTime;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class RecentOrder {
        private Long orderId;
        private Long voucherId;
        private LocalDateTime createTime;
    }

    // ==================== queue ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class QueueEvidence {

        private Instant observedAt;

        private StreamSummary mainStream;
        /** 只保留聚合信息，不输出消费者明细列表 */
        private ConsumerGroupSummary consumerGroup;
        private DeadLetterStream deadLetterStream;
        /** 仅 DEAD_LETTER 计划时采集；未计划时为 null */
        private List<DeadLetterEntry> deadLetterEntriesForVoucher;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class StreamSummary {
        private String key;
        private Boolean exists;
        private Long length;
        private String firstEntryId;
        private String lastEntryId;
        private String lastGeneratedId;
        private Long groupCount;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ConsumerGroupSummary {
        private String name;
        private Long consumersTotal;
        private Long pendingTotal;
        private String lastDeliveredId;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class DeadLetterStream {
        private String key;
        private Boolean exists;
        private Long length;
        /** 固定为 NEWEST：从最新端读取 */
        private String scannedFrom;
        private Integer scannedLimit;
        private Integer entriesFoundForVoucher;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class DeadLetterEntry {
        private String streamEntryId;
        private String originalMessageId;
        private Long voucherId;
        private Long orderId;
        private String failureReason;
    }

    // ==================== consumer health ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ConsumerHealthEvidence {

        private Instant observedAt;

        private String status;
        private String consumerStatus;
        private Boolean consumerAlive;
        private Long heartbeatAgeMs;
        private Long successHeartbeatAgeMs;
        private Long pendingCount;
        private String reason;
    }

    // ==================== runtime ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class RuntimeEvidence {

        private Instant observedAt;

        private Long uptimeMs;
        private Long heapUsedBytes;
        private Integer threadCount;
        private Integer availableProcessors;
        private String javaVersion;
    }

    // ==================== context quality ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ContextQuality {

        /** 只相对于本 Incident 的 planned_sources 判断；logs 未实现不影响该值 */
        private Boolean complete;

        private List<String> plannedSources;
        private List<String> availableSources;
        private List<String> unavailableSources;
        private List<String> notImplementedSources;

        private List<SourceError> errors;
        private List<Truncation> truncations;
        private List<String> notes;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class SourceError {
        private String source;
        /** {@link com.hmdp.enums.ContextSourceErrorType} 的名称 */
        private String errorType;
        /** 通用安全描述，不含原始异常信息 */
        private String message;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Truncation {
        private String source;
        private Integer limit;
        private Integer returned;
        private Boolean truncated;
    }
}
