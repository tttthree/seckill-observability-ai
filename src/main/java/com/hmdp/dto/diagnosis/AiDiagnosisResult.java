package com.hmdp.dto.diagnosis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * V2-4 诊断结果：Python V2-3 {@code DiagnosisResult} 的类型化镜像 + Java integration 层遥测。
 *
 * <p>
 * 契约约束：
 * </p>
 * <ul>
 *   <li>类级 {@code @JsonInclude(ALWAYS)}：全局 {@code spring.jackson.default-property-inclusion=non_null}
 *       下仍显式输出 {@code null}（与 V2-2 契约同一约定）；</li>
 *   <li>字段一律用包装类型：字段缺失（如被代理截断的响应体）会保留为 {@code null}，由客户端做契约校验，
 *       不会静默变成 0/空串；</li>
 *   <li>4 个 Java integration 字段使用字段级 {@code NON_NULL}：诊断成功时不污染响应；</li>
 *   <li>{@code error_code} 的归属由 {@link #errorOrigin} 明确区分：{@code PYTHON} = Python 原样返回，
 *       {@code JAVA_INTEGRATION} = Java 集成层生成（一律 {@code AI_*} 前缀）。</li>
 * </ul>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiDiagnosisResult {

    public static final String STATUS_DIAGNOSED = "DIAGNOSED";
    public static final String STATUS_INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    /** error_code 来自 Python 服务 */
    public static final String ORIGIN_PYTHON = "PYTHON";
    /** error_code 由 Java 集成层生成 */
    public static final String ORIGIN_JAVA_INTEGRATION = "JAVA_INTEGRATION";

    // ==================== Python DiagnosisResult 契约字段 ====================

    private String diagnosisStatus;
    private String contextVersion;
    /** Incident 主证据缺失时 Python 可返回 null（禁止用 0 当哨兵） */
    private Long incidentId;
    private String incidentType;
    private String rootCause;
    private List<Evidence> evidence = new ArrayList<>();
    private List<RecommendedAction> recommendedActions = new ArrayList<>();
    private String insufficientReason;
    private String errorCode;
    private EvidenceValidation evidenceValidation;
    private String model;
    private String promptVersion;
    private Instant diagnosedAt;
    private Integer elapsedMs;

    // ==================== Java integration 层遥测（仅 Java 生成） ====================

    /** error_code 归属：PYTHON / JAVA_INTEGRATION；无错误时为 null */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String errorOrigin;

    /** Java 降级时保留 Python 错误体里的 error_code（如 INVALID_CONTEXT / INTERNAL），不丢诊断线索 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String pythonErrorCode;

    /** Java 降级时实际收到的 HTTP 状态码；未收到响应为 0 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer httpStatus;

    /** Java 实际尝试次数：正常调用为 1~max-attempts；未发起调用（禁用/限流）为 0 */
    private Integer attempts;

    /**
     * 构造 Java 本地降级结果（Python 不可达 / 响应非法 / 被禁用 / 被限流）。
     *
     * <p>
     * 冻结语义：{@code diagnosis_status=UNAVAILABLE}、{@code root_cause=null}、证据与建议为空、
     * {@code model}/{@code prompt_version}/{@code diagnosed_at} 一律为 null
     * —— **不伪造任何 Python/模型侧信息**；{@code elapsed_ms} 保留 Java 实测值。
     * </p>
     */
    public static AiDiagnosisResult localUnavailable(String contextVersion,
                                                     Long incidentId,
                                                     String incidentType,
                                                     String errorCode,
                                                     String pythonErrorCode,
                                                     Integer httpStatus,
                                                     int attempts,
                                                     long elapsedMs) {
        AiDiagnosisResult result = new AiDiagnosisResult();
        result.setDiagnosisStatus(STATUS_UNAVAILABLE);
        result.setContextVersion(contextVersion);
        result.setIncidentId(incidentId);
        result.setIncidentType(incidentType);
        result.setRootCause(null);
        result.setEvidence(new ArrayList<>());
        result.setRecommendedActions(new ArrayList<>());
        result.setInsufficientReason(null);
        result.setErrorCode(errorCode);
        result.setEvidenceValidation(new EvidenceValidation(0, 0, 0, 0));
        result.setModel(null);
        result.setPromptVersion(null);
        result.setDiagnosedAt(null);
        result.setElapsedMs((int) elapsedMs);

        result.setErrorOrigin(errorCode == null ? null : ORIGIN_JAVA_INTEGRATION);
        result.setPythonErrorCode(pythonErrorCode);
        result.setHttpStatus(httpStatus);
        result.setAttempts(attempts);
        return result;
    }

    // ==================== 嵌套类型（与 Python 契约逐字段对应） ====================

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Evidence {

        private String path;

        /** Context 真实值（服务端回填），类型不定：string/number/list/object/null → 必须用 Object */
        private Object observed;

        private String note;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RecommendedAction {

        private String action;
        private String rationale;

        /** 恒为 true：服务永不自动执行修复动作 */
        private Boolean requiresHuman;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EvidenceValidation {

        private Integer submitted;
        private Integer accepted;
        private Integer dropped;
        /** 合法但被输出上限截断（V2-3.3）：不是错误 */
        private Integer overLimit;

        public EvidenceValidation() {
        }

        public EvidenceValidation(Integer submitted, Integer accepted, Integer dropped, Integer overLimit) {
            this.submitted = submitted;
            this.accepted = accepted;
            this.dropped = dropped;
            this.overLimit = overLimit;
        }
    }
}
