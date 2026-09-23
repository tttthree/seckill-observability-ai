package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AiDiagnosisConfig;
import com.hmdp.config.AiDiagnosisProperties;
import com.hmdp.dto.context.IncidentContext;
import com.hmdp.dto.diagnosis.AiDiagnosisRequest;
import com.hmdp.dto.diagnosis.AiDiagnosisResult;
import com.hmdp.service.AiDiagnosisClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;

/**
 * V2-4 诊断客户端实现：类型化透传 + 有界重试 + 稳定本地降级。
 *
 * <p>
 * 请求体只序列化一次（重试复用同一字符串），用 Spring Boot 注入的 {@link ObjectMapper}
 * （已注册 JavaTimeModule）保证 {@code Instant} 契约正确；响应先取 String 再显式反序列化，
 * 使 malformed 响应走可测的分支，而不是依赖 converter 报错。
 * </p>
 */
@Slf4j
@Service
public class AiDiagnosisClientImpl implements AiDiagnosisClient {

    // ==================== Java integration 层错误码（AI_ 前缀只由 Java 生成） ====================

    /** ai-diagnosis.enabled=false：未调用 Python */
    public static final String CODE_DISABLED = "AI_SERVICE_DISABLED";
    /** Sentinel 限流：未调用 Python */
    public static final String CODE_RATE_LIMITED = "AI_RATE_LIMITED";
    /** 连接阶段超时（可重试） */
    public static final String CODE_CONNECT_TIMEOUT = "AI_SERVICE_CONNECT_TIMEOUT";
    /** 读取阶段超时（**不重试**） */
    public static final String CODE_READ_TIMEOUT = "AI_SERVICE_READ_TIMEOUT";
    /** connection refused / 未知主机（可重试） */
    public static final String CODE_UNREACHABLE = "AI_SERVICE_UNREACHABLE";
    /** 其它 I/O 失败（不重试） */
    public static final String CODE_UNAVAILABLE = "AI_SERVICE_UNAVAILABLE";
    /** 5xx（仅 502/503/504 参与重试） */
    public static final String CODE_HTTP_5XX = "AI_SERVICE_HTTP_5XX";
    /** 其它 4xx（不重试） */
    public static final String CODE_HTTP_4XX = "AI_SERVICE_HTTP_4XX";
    /** 422/413：Java 送出的 Context 不被 Python 接受（不重试） */
    public static final String CODE_REQUEST_REJECTED = "AI_SERVICE_REQUEST_REJECTED";
    /** 200 但响应无法解析 / 违反 V2-3 冻结语义（不重试） */
    public static final String CODE_RESPONSE_INVALID = "AI_RESPONSE_INVALID";
    /** Java 侧请求序列化失败（不重试） */
    public static final String CODE_REQUEST_INVALID = "AI_REQUEST_INVALID";

    /** 允许重试的服务端状态码：仅网关/服务不可用语义的 502/503/504，**500 不重试** */
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(502, 503, 504);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AiDiagnosisProperties properties;

    public AiDiagnosisClientImpl(@Qualifier(AiDiagnosisConfig.REST_TEMPLATE_BEAN) RestTemplate restTemplate,
                                 ObjectMapper objectMapper,
                                 AiDiagnosisProperties properties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public AiDiagnosisResult diagnose(IncidentContext context) {
        long started = System.nanoTime();

        if (!properties.isEnabled()) {
            log.info("AI 诊断已关闭(ai-diagnosis.enabled=false)，本地降级 incidentId={}", incidentId(context));
            return local(context, CODE_DISABLED, null, null, 0, elapsedMs(started));
        }

        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(
                    new AiDiagnosisRequest().setIncidentContext(context));
        } catch (JsonProcessingException e) {
            // 只记录异常类型，不输出契约内容
            log.warn("AI 诊断请求序列化失败，本地降级 incidentId={}, type={}",
                    incidentId(context), e.getClass().getSimpleName());
            return local(context, CODE_REQUEST_INVALID, null, null, 0, elapsedMs(started));
        }

        int maxAttempts = Math.max(1, properties.getMaxAttempts());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        properties.getUrl(), HttpMethod.POST,
                        new HttpEntity<>(requestJson, jsonHeaders()), String.class);
                return interpretResponse(context, response, attempt, elapsedMs(started));

            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                String pythonErrorCode = extractPythonErrorCode(e.getResponseBodyAsString());

                if (RETRYABLE_STATUS.contains(status) && attempt < maxAttempts) {
                    log.warn("AI 诊断服务返回 {}，将重试一次 incidentId={}", status, incidentId(context));
                    continue;
                }
                String code = classifyHttpStatus(status);
                log.warn("AI 诊断调用失败，本地降级 incidentId={}, httpStatus={}, code={}",
                        incidentId(context), status, code);
                return local(context, code, pythonErrorCode, status, attempt, elapsedMs(started));

            } catch (ResourceAccessException e) {
                String code = classifyAccessError(e);
                if (isRetryableAccessError(code) && attempt < maxAttempts) {
                    log.warn("AI 诊断连接失败({})，将重试一次 incidentId={}", code, incidentId(context));
                    continue;
                }
                log.warn("AI 诊断调用失败，本地降级 incidentId={}, code={}", incidentId(context), code);
                return local(context, code, null, null, attempt, elapsedMs(started));

            } catch (RestClientException e) {
                // 传输层其它异常（如 converter 层）：不重试，按响应不可用处理
                log.warn("AI 诊断调用异常，本地降级 incidentId={}, type={}",
                        incidentId(context), e.getClass().getSimpleName());
                return local(context, CODE_RESPONSE_INVALID, null, null, attempt, elapsedMs(started));
            }
        }

        // 循环内所有分支均已 return，这里仅作为编译器兜底
        return local(context, CODE_UNAVAILABLE, null, null, maxAttempts, elapsedMs(started));
    }

    @Override
    public AiDiagnosisResult rateLimited(IncidentContext context) {
        return local(context, CODE_RATE_LIMITED, null, null, 0, 0L);
    }

    // ==================== 响应处理与 V2-3 冻结语义校验 ====================

    private AiDiagnosisResult interpretResponse(IncidentContext context, ResponseEntity<String> response,
                                                int attempt, long elapsedMs) {
        int status = response.getStatusCode().value();
        String body = response.getBody();

        if (!StringUtils.hasText(body)) {
            log.warn("AI 诊断响应为空，本地降级 incidentId={}, httpStatus={}", incidentId(context), status);
            return local(context, CODE_RESPONSE_INVALID, null, status, attempt, elapsedMs);
        }

        AiDiagnosisResult result;
        try {
            result = objectMapper.readValue(body, AiDiagnosisResult.class);
        } catch (JsonProcessingException e) {
            log.warn("AI 诊断响应无法解析，本地降级 incidentId={}, type={}",
                    incidentId(context), e.getClass().getSimpleName());
            return local(context, CODE_RESPONSE_INVALID, null, status, attempt, elapsedMs);
        }

        String violation = validatePythonResult(result);
        if (violation != null) {
            log.warn("AI 诊断响应违反 V2-3 冻结语义，本地降级 incidentId={}, violation={}",
                    incidentId(context), violation);
            return local(context, CODE_RESPONSE_INVALID, null, status, attempt, elapsedMs);
        }

        result.setAttempts(attempt);
        if (result.getErrorCode() != null) {
            // 200 + UNAVAILABLE：错误码由 Python 生成
            result.setErrorOrigin(AiDiagnosisResult.ORIGIN_PYTHON);
        }
        log.info("AI 诊断完成 incidentId={}, status={}, attempts={}, elapsedMs={}",
                result.getIncidentId(), result.getDiagnosisStatus(), attempt, elapsedMs);
        return result;
    }

    /**
     * 校验 Python 200 响应的契约完整性与 V2-3 冻结语义。
     *
     * @return {@code null} 表示通过；否则返回违反原因（仅用于日志，不含响应内容）
     */
    private String validatePythonResult(AiDiagnosisResult result) {
        if (!StringUtils.hasText(result.getContextVersion())) {
            return "missing context_version";
        }
        String status = result.getDiagnosisStatus();
        if (status == null) {
            return "missing diagnosis_status";
        }
        if (result.getEvidence() == null) {
            return "missing evidence";
        }
        if (result.getRecommendedActions() == null) {
            return "missing recommended_actions";
        }
        if (result.getPromptVersion() == null) {
            return "missing prompt_version";
        }
        if (result.getDiagnosedAt() == null) {
            return "missing diagnosed_at";
        }
        if (result.getElapsedMs() == null) {
            return "missing elapsed_ms";
        }

        AiDiagnosisResult.EvidenceValidation validation = result.getEvidenceValidation();
        if (validation == null) {
            return "missing evidence_validation";
        }
        if (validation.getSubmitted() == null || validation.getAccepted() == null
                || validation.getDropped() == null || validation.getOverLimit() == null) {
            return "incomplete evidence_validation";
        }
        // V2-3.3 冻结不变量
        if (validation.getSubmitted() != validation.getAccepted()
                + validation.getDropped() + validation.getOverLimit()) {
            return "evidence_validation invariant violated";
        }
        if (result.getEvidence().size() != validation.getAccepted()) {
            return "evidence size != accepted";
        }

        switch (status) {
            case AiDiagnosisResult.STATUS_DIAGNOSED:
                if (!StringUtils.hasText(result.getRootCause())) {
                    return "DIAGNOSED without root_cause";
                }
                if (validation.getAccepted() <= 0) {
                    return "DIAGNOSED without accepted evidence";
                }
                return null;
            case AiDiagnosisResult.STATUS_INSUFFICIENT_EVIDENCE:
                if (result.getRootCause() != null) {
                    return "INSUFFICIENT_EVIDENCE with root_cause";
                }
                if (!result.getRecommendedActions().isEmpty()) {
                    return "INSUFFICIENT_EVIDENCE with recommended_actions";
                }
                if (result.getErrorCode() != null) {
                    return "INSUFFICIENT_EVIDENCE with error_code";
                }
                return null;
            case AiDiagnosisResult.STATUS_UNAVAILABLE:
                if (!StringUtils.hasText(result.getErrorCode())) {
                    return "UNAVAILABLE without error_code";
                }
                return null;
            default:
                return "unknown diagnosis_status";
        }
    }

    // ==================== 失败分类 ====================

    private String classifyHttpStatus(int status) {
        if (status == 422 || status == 413) {
            return CODE_REQUEST_REJECTED;
        }
        if (status >= 500) {
            return CODE_HTTP_5XX;
        }
        return CODE_HTTP_4XX;
    }

    /**
     * I/O 异常分类。
     *
     * <p>
     * {@code HttpURLConnection} 不暴露「超时发生在连接阶段还是读取阶段」，只能按异常类型与消息做
     * best-effort 判定：无法确定时一律按**读取超时**处理（不重试），
     * 宁可少一次重试，也不违反「read timeout 不重试」的冻结策略。
     * </p>
     */
    private String classifyAccessError(ResourceAccessException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        if (cause instanceof ConnectException) {
            return CODE_UNREACHABLE;
        }
        if (cause instanceof SocketTimeoutException) {
            String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
            return message.contains("connect") ? CODE_CONNECT_TIMEOUT : CODE_READ_TIMEOUT;
        }
        return CODE_UNAVAILABLE;
    }

    private boolean isRetryableAccessError(String code) {
        return CODE_CONNECT_TIMEOUT.equals(code) || CODE_UNREACHABLE.equals(code);
    }

    /** 从 Python 错误体里取 error_code；不记录、不回显响应正文 */
    private String extractPythonErrorCode(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode code = node == null ? null : node.get("error_code");
            return code != null && code.isTextual() ? code.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 工具方法 ====================

    private AiDiagnosisResult local(IncidentContext context, String errorCode, String pythonErrorCode,
                                    Integer httpStatus, int attempts, long elapsedMs) {
        String contextVersion = null;
        Long incidentId = null;
        String incidentType = null;
        if (context != null) {
            contextVersion = context.getContextVersion();
            if (context.getIncident() != null) {
                incidentId = context.getIncident().getIncidentId();
                incidentType = context.getIncident().getIncidentType();
            }
        }
        return AiDiagnosisResult.localUnavailable(contextVersion, incidentId, incidentType,
                errorCode, pythonErrorCode, httpStatus, attempts, elapsedMs);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private static Long incidentId(IncidentContext context) {
        if (context == null || context.getIncident() == null) {
            return null;
        }
        return context.getIncident().getIncidentId();
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
