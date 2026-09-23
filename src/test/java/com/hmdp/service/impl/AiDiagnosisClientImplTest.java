package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hmdp.config.AiDiagnosisProperties;
import com.hmdp.dto.context.IncidentContext;
import com.hmdp.dto.diagnosis.AiDiagnosisResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * V2-4 诊断客户端可靠性矩阵：透传 / 校验 / 重试 / 降级分类。
 *
 * <p>
 * 用 {@link MockRestServiceServer} 精确断言「是否发生重试」（期望次数 + verify），
 * 并用真实 socket（{@link ServerSocket} 关闭端口、{@link HttpServer} 慢响应）覆盖
 * connection refused 与 read timeout 两条真实 I/O 路径。
 * </p>
 */
class AiDiagnosisClientImplTest {

    private static final String DIAGNOSED_BODY = "{"
            + "\"diagnosis_status\":\"DIAGNOSED\","
            + "\"context_version\":\"v2-2.1\","
            + "\"incident_id\":9001,"
            + "\"incident_type\":\"INVENTORY_MISMATCH\","
            + "\"root_cause\":\"券维度 Redis 库存采集中断\","
            + "\"evidence\":["
            + "{\"path\":\"incident.status\",\"observed\":\"RESOLVED\",\"note\":\"事件已恢复\"},"
            + "{\"path\":\"metrics.counters.total_requests\",\"observed\":2.0,\"note\":null}],"
            + "\"recommended_actions\":[{\"action\":\"人工核对库存\",\"rationale\":\"不自动覆盖\","
            + "\"requires_human\":true}],"
            + "\"insufficient_reason\":null,"
            + "\"error_code\":null,"
            + "\"evidence_validation\":{\"submitted\":3,\"accepted\":2,\"dropped\":1,\"over_limit\":0},"
            + "\"model\":\"deepseek-flash\","
            + "\"prompt_version\":\"v2-3.1\","
            + "\"diagnosed_at\":\"2026-09-23T14:02:29.982545Z\","
            + "\"elapsed_ms\":3625}";

    private static final String INSUFFICIENT_BODY = "{"
            + "\"diagnosis_status\":\"INSUFFICIENT_EVIDENCE\","
            + "\"context_version\":\"v2-2.1\","
            + "\"incident_id\":9001,"
            + "\"incident_type\":\"INVENTORY_MISMATCH\","
            + "\"root_cause\":null,"
            + "\"evidence\":[],"
            + "\"recommended_actions\":[],"
            + "\"insufficient_reason\":\"缺少同时刻库存证据\","
            + "\"error_code\":null,"
            + "\"evidence_validation\":{\"submitted\":0,\"accepted\":0,\"dropped\":0,\"over_limit\":0},"
            + "\"model\":\"deepseek-flash\","
            + "\"prompt_version\":\"v2-3.1\","
            + "\"diagnosed_at\":\"2026-09-23T14:02:29.982545Z\","
            + "\"elapsed_ms\":1200}";

    private static final String UNAVAILABLE_BODY = "{"
            + "\"diagnosis_status\":\"UNAVAILABLE\","
            + "\"context_version\":\"v2-2.1\","
            + "\"incident_id\":9001,"
            + "\"incident_type\":\"INVENTORY_MISMATCH\","
            + "\"root_cause\":null,"
            + "\"evidence\":[],"
            + "\"recommended_actions\":[],"
            + "\"insufficient_reason\":null,"
            + "\"error_code\":\"MODEL_NOT_CONFIGURED\","
            + "\"evidence_validation\":{\"submitted\":0,\"accepted\":0,\"dropped\":0,\"over_limit\":0},"
            + "\"model\":\"deepseek-flash\","
            + "\"prompt_version\":\"v2-3.1\","
            + "\"diagnosed_at\":\"2026-09-23T14:02:29.982545Z\","
            + "\"elapsed_ms\":1}";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AiDiagnosisProperties properties;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private AiDiagnosisClientImpl client;

    @BeforeEach
    void setUp() {
        properties = new AiDiagnosisProperties();
        restTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new AiDiagnosisClientImpl(restTemplate, objectMapper, properties);
    }

    // ==================== 正常透传 ====================

    @Test
    void shouldPassThroughDiagnosedResultWithoutRetry() {
        server.expect(requestTo(properties.getUrl()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(DIAGNOSED_BODY, MediaType.APPLICATION_JSON));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertEquals(AiDiagnosisResult.STATUS_DIAGNOSED, result.getDiagnosisStatus());
        assertEquals(1, result.getAttempts());
        assertEquals("券维度 Redis 库存采集中断", result.getRootCause());
        assertEquals(2, result.getEvidence().size());
        assertEquals("RESOLVED", result.getEvidence().get(0).getObserved());
        assertEquals(2.0, result.getEvidence().get(1).getObserved());
        assertEquals(Boolean.TRUE, result.getRecommendedActions().get(0).getRequiresHuman());
        assertEquals(1, result.getEvidenceValidation().getDropped());
        assertEquals(0, result.getEvidenceValidation().getOverLimit());
        assertEquals("deepseek-flash", result.getModel());
        assertEquals(Instant.parse("2026-09-23T14:02:29.982545Z"), result.getDiagnosedAt());
        assertEquals(3625, result.getElapsedMs());
        assertNull(result.getErrorCode());
        assertNull(result.getErrorOrigin());
        assertNull(result.getPythonErrorCode());
        // 已收到 HTTP 响应：http_status 保留真实状态码
        assertEquals(200, result.getHttpStatus());
    }

    @Test
    void shouldPassThroughInsufficientEvidence() {
        server.expect(requestTo(properties.getUrl()))
                .andRespond(withSuccess(INSUFFICIENT_BODY, MediaType.APPLICATION_JSON));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertEquals(AiDiagnosisResult.STATUS_INSUFFICIENT_EVIDENCE, result.getDiagnosisStatus());
        assertNull(result.getRootCause());
        assertTrue(result.getEvidence().isEmpty());
        assertTrue(result.getRecommendedActions().isEmpty());
        assertNull(result.getErrorCode());
        assertEquals(1, result.getAttempts());
        assertEquals(200, result.getHttpStatus());
    }

    @Test
    void shouldPassThroughPythonUnavailableWithoutRetry() {
        server.expect(requestTo(properties.getUrl()))
                .andRespond(withSuccess(UNAVAILABLE_BODY, MediaType.APPLICATION_JSON));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertEquals(AiDiagnosisResult.STATUS_UNAVAILABLE, result.getDiagnosisStatus());
        assertEquals("MODEL_NOT_CONFIGURED", result.getErrorCode());
        assertEquals(AiDiagnosisResult.ORIGIN_PYTHON, result.getErrorOrigin());
        assertEquals(1, result.getAttempts());
        assertEquals(200, result.getHttpStatus());
    }

    @Test
    void shouldSerializeFrozenContractRequestBody() {
        server.expect(requestTo(properties.getUrl()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.incident_context.context_version").value("v2-2.1"))
                .andExpect(jsonPath("$.incident_context.incident.incident_id").value(9001))
                .andExpect(jsonPath("$.incident_context.incident.incident_type").value("INVENTORY_MISMATCH"))
                .andRespond(withSuccess(DIAGNOSED_BODY, MediaType.APPLICATION_JSON));

        client.diagnose(context());

        server.verify();
    }

    // ==================== 请求相关性校验（防串包 / 版本错配） ====================

    @Test
    void shouldRejectContextVersionMismatch() {
        String body = DIAGNOSED_BODY.replace("\"context_version\":\"v2-2.1\"",
                "\"context_version\":\"v2-9.9\"");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectIncidentIdMismatch() {
        String body = DIAGNOSED_BODY.replace("\"incident_id\":9001", "\"incident_id\":7002");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectIncidentTypeMismatch() {
        String body = DIAGNOSED_BODY.replace("\"incident_type\":\"INVENTORY_MISMATCH\"",
                "\"incident_type\":\"DEAD_LETTER\"");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    // ==================== 200 但违反 V2-3 冻结语义 → AI_RESPONSE_INVALID（不重试） ====================

    @Test
    void shouldRejectInvariantViolation() {
        String body = DIAGNOSED_BODY.replace(
                "\"submitted\":3,\"accepted\":2,\"dropped\":1,\"over_limit\":0",
                "\"submitted\":3,\"accepted\":2,\"dropped\":1,\"over_limit\":1");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectEvidenceSizeMismatch() {
        String body = DIAGNOSED_BODY.replace("\"accepted\":2", "\"accepted\":1");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectDiagnosedWithoutRootCause() {
        String body = DIAGNOSED_BODY.replace("\"root_cause\":\"券维度 Redis 库存采集中断\"", "\"root_cause\":null");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectDiagnosedWithoutAcceptedEvidence() {
        String body = DIAGNOSED_BODY
                .replace("\"submitted\":3,\"accepted\":2,\"dropped\":1,\"over_limit\":0",
                        "\"submitted\":3,\"accepted\":0,\"dropped\":3,\"over_limit\":0")
                .replace("{\"path\":\"incident.status\",\"observed\":\"RESOLVED\",\"note\":\"事件已恢复\"},", "")
                .replace("{\"path\":\"metrics.counters.total_requests\",\"observed\":2.0,\"note\":null}", "");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectInsufficientEvidenceWithRootCause() {
        String body = INSUFFICIENT_BODY.replace("\"root_cause\":null", "\"root_cause\":\"编造根因\"");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectInsufficientEvidenceWithActions() {
        String body = INSUFFICIENT_BODY.replace("\"recommended_actions\":[]",
                "\"recommended_actions\":[{\"action\":\"a\",\"rationale\":\"r\",\"requires_human\":true}]");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectInsufficientEvidenceWithErrorCode() {
        String body = INSUFFICIENT_BODY.replace("\"error_code\":null", "\"error_code\":\"INTERNAL\"");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectUnavailableWithoutErrorCode() {
        String body = UNAVAILABLE_BODY.replace("\"error_code\":\"MODEL_NOT_CONFIGURED\"", "\"error_code\":null");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectUnknownDiagnosisStatus() {
        String body = DIAGNOSED_BODY.replace("\"diagnosis_status\":\"DIAGNOSED\"",
                "\"diagnosis_status\":\"PARTIALLY_DIAGNOSED\"");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectMissingEvidenceValidation() {
        String body = DIAGNOSED_BODY.replace(
                "\"evidence_validation\":{\"submitted\":3,\"accepted\":2,\"dropped\":1,\"over_limit\":0},",
                "\"evidence_validation\":null,");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectIncompleteEvidenceValidation() {
        String body = DIAGNOSED_BODY.replace(
                "\"submitted\":3,\"accepted\":2,\"dropped\":1,\"over_limit\":0",
                "\"submitted\":3,\"accepted\":2,\"dropped\":1");
        assertDegradedAfterSingleCall(body, AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldDegradeOnMalformedBody() {
        assertDegradedAfterSingleCall("not-a-json", AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    @Test
    void shouldDegradeOnEmptyBody() {
        assertDegradedAfterSingleCall("", AiDiagnosisClientImpl.CODE_RESPONSE_INVALID);
    }

    // ==================== HTTP 失败分类与重试边界 ====================

    @Test
    void shouldRetryOnceThenSucceedOn503() {
        server.expect(requestTo(properties.getUrl())).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(properties.getUrl()))
                .andRespond(withSuccess(DIAGNOSED_BODY, MediaType.APPLICATION_JSON));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertEquals(AiDiagnosisResult.STATUS_DIAGNOSED, result.getDiagnosisStatus());
        assertEquals(2, result.getAttempts(), "503 必须恰好重试 1 次");
        assertEquals(200, result.getHttpStatus());
    }

    @Test
    void shouldDegradeAfterRetryExhaustedOn503() {
        server.expect(times(2), requestTo(properties.getUrl()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_HTTP_5XX, 2, 503);
    }

    @Test
    void shouldUseLastServerErrorCodeWhenRetrying502() {
        server.expect(requestTo(properties.getUrl())).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(properties.getUrl())).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_HTTP_5XX, 2, 504);
    }

    @Test
    void shouldNotRetryOn500() {
        server.expect(requestTo(properties.getUrl()))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"error_code\":\"INTERNAL\",\"message\":\"内部错误\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_HTTP_5XX, 1, 500);
        assertEquals("INTERNAL", result.getPythonErrorCode());
    }

    @Test
    void shouldNotRetryOn422AndKeepPythonErrorCode() {
        server.expect(requestTo(properties.getUrl()))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("{\"error_code\":\"INVALID_CONTEXT\",\"message\":\"incident_context 不符合契约\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_REQUEST_REJECTED, 1, 422);
        assertEquals("INVALID_CONTEXT", result.getPythonErrorCode());
    }

    @Test
    void shouldNotRetryOn413AndKeepPythonErrorCode() {
        server.expect(requestTo(properties.getUrl()))
                .andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE)
                        .body("{\"error_code\":\"CONTEXT_TOO_LARGE\",\"context_chars\":253048,\"limit\":200000}")
                        .contentType(MediaType.APPLICATION_JSON));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_REQUEST_REJECTED, 1, 413);
        assertEquals("CONTEXT_TOO_LARGE", result.getPythonErrorCode());
    }

    @Test
    void shouldNotRetryOnOther4xx() {
        server.expect(requestTo(properties.getUrl())).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_HTTP_4XX, 1, 400);
    }

    // ==================== 尝试次数硬上限 [1,2] ====================

    @Test
    void shouldClampConfiguredAttemptsToHardMaximum() {
        // 即使配置成 99，也最多总尝试 2 次：mock server 只准备 2 次响应
        properties.setMaxAttempts(99);
        server.expect(times(2), requestTo(properties.getUrl()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_HTTP_5XX, 2, 503);
    }

    @Test
    void shouldClampConfiguredAttemptsToHardMaximumOnRetryableIoError() {
        properties.setMaxAttempts(50);
        server.expect(times(2), requestTo(properties.getUrl()))
                .andRespond(request -> {
                    throw new ResourceAccessException("Connection refused",
                            new ConnectException("Connection refused"));
                });

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_UNREACHABLE, 2, 0);
    }

    @Test
    void shouldTreatNonPositiveConfiguredAttemptsAsSingleAttempt() {
        properties.setMaxAttempts(0);
        server.expect(requestTo(properties.getUrl())).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_HTTP_5XX, 1, 503);
    }

    // ==================== I/O 失败分类与重试边界 ====================

    @Test
    void shouldRetryOnConnectTimeout() {
        server.expect(times(2), requestTo(properties.getUrl()))
                .andRespond(request -> {
                    throw new ResourceAccessException("Connect timed out",
                            new SocketTimeoutException("Connect timed out"));
                });

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_CONNECT_TIMEOUT, 2, 0);
    }

    @Test
    void shouldNotRetryOnReadTimeout() {
        server.expect(requestTo(properties.getUrl()))
                .andRespond(request -> {
                    throw new ResourceAccessException("Read timed out",
                            new SocketTimeoutException("Read timed out"));
                });

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_READ_TIMEOUT, 1, 0);
    }

    @Test
    void shouldRetryOnConnectionRefused() {
        server.expect(times(2), requestTo(properties.getUrl()))
                .andRespond(request -> {
                    throw new ResourceAccessException("Connection refused",
                            new ConnectException("Connection refused"));
                });

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_UNREACHABLE, 2, 0);
    }

    @Test
    void shouldRetryOnUnknownHost() {
        server.expect(times(2), requestTo(properties.getUrl()))
                .andRespond(request -> {
                    throw new ResourceAccessException("ai-diagnosis.invalid: Name or service not known",
                            new UnknownHostException("ai-diagnosis.invalid"));
                });

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_UNREACHABLE, 2, 0);
    }

    @Test
    void shouldRetryOnUnknownHostNestedInCauseChain() {
        // cause chain: ResourceAccessException -> IOException -> UnknownHostException
        server.expect(times(2), requestTo(properties.getUrl()))
                .andRespond(request -> {
                    throw new ResourceAccessException("request failed",
                            new IOException("wrapped", new UnknownHostException("ai-diagnosis.invalid")));
                });

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_UNREACHABLE, 2, 0);
    }

    @Test
    void shouldNotRetryOnOtherIoFailure() {
        server.expect(requestTo(properties.getUrl()))
                .andRespond(request -> {
                    throw new ResourceAccessException("unexpected io failure");
                });

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_UNAVAILABLE, 1, 0);
    }

    // ==================== 本地配置 / 禁用 / 限流：绝不调用 Python、绝不抛异常 ====================

    @Test
    void shouldDegradeOnLocalUriConfigurationError() {
        RestTemplate failingTemplate = mock(RestTemplate.class);
        when(failingTemplate.exchange(anyString(), any(HttpMethod.class), any(), eq(String.class)))
                .thenThrow(new IllegalArgumentException("URI is not absolute"));

        AiDiagnosisClientImpl badUrlClient =
                new AiDiagnosisClientImpl(failingTemplate, objectMapper, properties);

        AiDiagnosisResult result = badUrlClient.diagnose(context());

        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_URL_INVALID, 1, 0);
    }

    @Test
    void shouldDegradeOnMalformedConfiguredUrl() {
        // 典型误配置：AI_DIAGNOSIS_URL 少了 scheme（真实路径实测为 IllegalArgumentException）
        properties.setUrl("localhost:8000/api/v1/diagnosis");

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_URL_INVALID, 1, 0);
    }

    @Test
    void shouldDegradeOnUrlWithOutOfRangePort() {
        // 端口越界由真实请求工厂在建立连接前判定（MockRestServiceServer 会绕过该校验，故这里用真实 factory）
        AiDiagnosisProperties badProperties = new AiDiagnosisProperties();
        badProperties.setUrl("http://127.0.0.1:99999/api/v1/diagnosis");
        AiDiagnosisClientImpl badClient = new AiDiagnosisClientImpl(
                new RestTemplate(new SimpleClientHttpRequestFactory()), objectMapper, badProperties);

        AiDiagnosisResult result = badClient.diagnose(context());

        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_URL_INVALID, 1, 0);
    }

    @Test
    void shouldSkipHttpCallWhenDisabled() {
        properties.setEnabled(false);

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_DISABLED, 0, 0);
    }

    @Test
    void shouldReturnRateLimitedWithoutHttpCall() {
        AiDiagnosisResult result = client.rateLimited(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_RATE_LIMITED, 0, 0);
    }

    @Test
    void shouldDegradeWhenRequestSerializationFails() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("boom") {
                });
        AiDiagnosisClientImpl failingClient =
                new AiDiagnosisClientImpl(restTemplate, failingMapper, properties);

        AiDiagnosisResult result = failingClient.diagnose(context());

        server.verify();
        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_REQUEST_INVALID, 0, 0);
    }

    // ==================== 真实 socket 路径 ====================

    @Test
    void shouldDegradeOnRealConnectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        AiDiagnosisProperties realProperties = new AiDiagnosisProperties();
        realProperties.setUrl("http://127.0.0.1:" + closedPort + "/api/v1/diagnosis");
        AiDiagnosisClientImpl realClient = new AiDiagnosisClientImpl(
                new RestTemplate(new SimpleClientHttpRequestFactory()), objectMapper, realProperties);

        AiDiagnosisResult result = realClient.diagnose(context());

        assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_UNREACHABLE, 2, 0);
    }

    @Test
    void shouldNotRetryReadTimeoutOnRealSlowServer() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        httpServer.createContext("/api/v1/diagnosis", exchange -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(1500L);
                byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            } catch (Exception ignored) {
                // 客户端读超时后主动断开连接属于预期行为
            } finally {
                exchange.close();
            }
        });
        httpServer.start();

        try {
            AiDiagnosisProperties slowProperties = new AiDiagnosisProperties();
            slowProperties.setUrl("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/api/v1/diagnosis");
            slowProperties.setReadTimeoutMs(300);
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(slowProperties.getConnectTimeoutMs());
            factory.setReadTimeout(slowProperties.getReadTimeoutMs());

            AiDiagnosisClientImpl slowClient =
                    new AiDiagnosisClientImpl(new RestTemplate(factory), objectMapper, slowProperties);

            AiDiagnosisResult result = slowClient.diagnose(context());

            assertJavaDegradation(result, AiDiagnosisClientImpl.CODE_READ_TIMEOUT, 1, 0);
            assertEquals(1, hits.get(), "读取超时不得重试");
        } finally {
            httpServer.stop(0);
        }
    }

    // ==================== helpers ====================

    private void assertDegradedAfterSingleCall(String body, String expectedCode) {
        server.expect(requestTo(properties.getUrl()))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        AiDiagnosisResult result = client.diagnose(context());

        server.verify();
        assertJavaDegradation(result, expectedCode, 1, 200);
    }

    /** Java 本地降级的统一冻结语义 */
    private static void assertJavaDegradation(AiDiagnosisResult result, String expectedCode,
                                              int expectedAttempts, int expectedHttpStatus) {
        assertNotNull(result);
        assertEquals(AiDiagnosisResult.STATUS_UNAVAILABLE, result.getDiagnosisStatus(), "降级结果必须是 UNAVAILABLE");
        assertEquals(expectedCode, result.getErrorCode());
        assertEquals(AiDiagnosisResult.ORIGIN_JAVA_INTEGRATION, result.getErrorOrigin());
        assertEquals(expectedAttempts, result.getAttempts());
        // http_status 永不缺字段：未收到响应为 0，收到响应为真实状态码
        assertNotNull(result.getHttpStatus(), "http_status 不得为 null");
        assertEquals(expectedHttpStatus, result.getHttpStatus());
        assertNull(result.getRootCause(), "禁止伪造成 DIAGNOSED");
        assertTrue(result.getEvidence().isEmpty());
        assertTrue(result.getRecommendedActions().isEmpty());
        assertNull(result.getInsufficientReason());
        assertNull(result.getModel(), "不得伪造模型侧信息");
        assertNull(result.getPromptVersion(), "不得伪造 prompt 版本");
        assertNull(result.getDiagnosedAt(), "不得伪造诊断时刻");
        assertNotNull(result.getElapsedMs());
        assertEquals(0, result.getEvidenceValidation().getSubmitted());
        assertEquals(0, result.getEvidenceValidation().getAccepted());
        assertEquals(0, result.getEvidenceValidation().getDropped());
        assertEquals(0, result.getEvidenceValidation().getOverLimit());
        assertEquals("v2-2.1", result.getContextVersion());
        assertEquals(9001L, result.getIncidentId());
        assertEquals("INVENTORY_MISMATCH", result.getIncidentType());
    }

    private static IncidentContext context() {
        return new IncidentContext()
                .setContextVersion("v2-2.1")
                .setBuiltAt(Instant.parse("2026-09-23T14:00:00Z"))
                .setIncident(new IncidentContext.IncidentEvidence()
                        .setObservedAt(Instant.parse("2026-09-23T14:00:00Z"))
                        .setIncidentId(9001L)
                        .setIncidentType("INVENTORY_MISMATCH"));
    }
}
