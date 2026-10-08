package com.hmdp.dto.context;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * IncidentContext v3.0：围绕单个 Incident 的真实运行证据集合，作为 Java→Python 的稳定输入契约。
 *
 * <p>
 * 契约原则（冻结）：
 * </p>
 * <ul>
 *   <li><b>只定义当前真正能采、且三类 Incident 诊断真正需要的字段</b>。没有可靠采集能力的信息
 *       （结构化日志、消费者组 lag / entries_read）在 Schema 中<b>直接不存在</b>，
 *       不以 null 或"未实现"标记出现；</li>
 *   <li>只放真实读取到的数据，不做任何 root cause 推断；</li>
 *   <li>每个嵌套段都声明 {@code @JsonInclude(ALWAYS)}，保证 nullable 字段真实序列化为 null；</li>
 *   <li>{@code unavailable_sources} 只记录<b>本次本应采集、但实际读取失败</b>的数据源名称；
 *       未计划采集的数据源段为 null 且<b>不</b>出现在其中——两者语义必须可区分；</li>
 *   <li>契约中不输出任何 Redis key 字符串：AI 不需要知道 key 名，只需要知道值是否存在。</li>
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

    /** Incident 主证据；仅在 Incident 不存在时为 null（此时 Controller 返回 404） */
    private IncidentEvidence incident;

    /** 未计划采集或读取失败时为 null */
    private RedisEvidence redis;
    private DatabaseEvidence database;
    private QueueEvidence queue;
    private ConsumerHealthEvidence consumerHealth;

    /** 本次本应采集但读取失败的数据源名称（如 "redis"）；未计划采集的不计入 */
    private List<String> unavailableSources = new ArrayList<>();

    // ==================== incident ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class IncidentEvidence {

        private Long incidentId;
        private String incidentType;
        private String severity;
        private String status;
        private Long relatedVoucherId;
        private Integer occurrenceCount;

        /** 数据库存的是无时区的 LocalDateTime，原样输出 */
        private LocalDateTime firstDetectedAt;
        private LocalDateTime lastDetectedAt;
        private LocalDateTime resolvedAt;

        private String title;
        private String description;

        /**
         * 按 IncidentType 白名单投影后的检测证据；无证据或解析失败时为 null。
         *
         * <p>
         * 契约：key 存在而 value=null 的 entry（如 redis_stock / deviation）必须真实输出为 {@code "key":null}。
         * 全局 {@code spring.jackson.default-property-inclusion=non_null} 会同时作用于 Map 的 content，
         * 类级 {@code @JsonInclude(ALWAYS)} 不覆盖 content，故字段级同时声明 value/content 的 ALWAYS。
         * </p>
         */
        @JsonInclude(value = JsonInclude.Include.ALWAYS, content = JsonInclude.Include.ALWAYS)
        private Map<String, Object> detectedSnapshot;
    }

    // ==================== redis（券维度） ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class RedisEvidence {

        /** 库存 key 的真实读取结果：present=false 时 value 必须为 null，不得写成 0 */
        private StockState stock;

        /**
         * 当前券已获得下单资格的用户数（资格 Set 的基数）。
         * <p>
         * Set 不存在时语义就是 0（正常的空集合），因此该字段恒有值、不为 null；
         * "Redis 读取失败"由 {@code redis=null} + {@code unavailable_sources=["redis"]} 表达。
         */
        private Long orderedUserCount;

        /** 当前券是否在 dirty（待对账）集合中 */
        private Boolean dirty;

        /** 当前券是否存在"两阶段对账首次发现不一致"标记 */
        private Boolean mismatchPending;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class StockState {
        /** 库存 key 是否存在；false 表示 Redis 中确实没有这个 key（真实故障证据） */
        private Boolean present;
        /** present=false 时必须为 null */
        private Long value;
    }

    // ==================== database ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class DatabaseEvidence {

        /** 券在数据库中是否存在；false 时 stock 必须为 null */
        private Boolean voucherExists;

        /** 券不存在时为 null */
        private Integer stock;

        /** 该券的订单总数（含未提交完成的历史订单） */
        private Long orderCount;

        /** 最近订单，按创建时间倒序，最多 {@code RECENT_ORDERS_LIMIT} 条；不含 user_id */
        private List<RecentOrder> recentOrders;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class RecentOrder {
        private Long orderId;
        private LocalDateTime createTime;
    }

    // ==================== queue ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class QueueEvidence {

        /** 消费者组 PEL 中尚未 ACK 的消息数（XPENDING） */
        private Long pendingCount;

        /**
         * 与当前券相关的死信条目（从最新端有界扫描后过滤）。
         * <p>
         * 刻意不提供整个死信流的 XLEN：global 死信数 &gt; 0 不代表当前券有死信，
         * 下发会造成维度歧义。判断"该券是否仍有死信"只看本列表是否非空。
         */
        private List<DeadLetterEntry> deadLetters;
    }

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class DeadLetterEntry {
        /** 原主 Stream 的消息 id（唯一 id，不额外暴露死信流自身的 entry id） */
        private String messageId;
        private Long orderId;
        private String failureReason;
    }

    // ==================== consumer health ====================

    @Data
    @Accessors(chain = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ConsumerHealthEvidence {

        /** 由 Actuator UP/DOWN 与内部 consumer_status 投影出的单一状态：HEALTHY / DEGRADED / DOWN */
        private String status;

        private Long heartbeatAgeMs;
        private Long successHeartbeatAgeMs;
        private Long pendingCount;
        private String reason;
    }
}
