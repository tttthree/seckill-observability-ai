package com.hmdp.controller;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hmdp.config.AiDiagnosisConfig;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.context.IncidentContext;
import com.hmdp.dto.diagnosis.AiDiagnosisResult;
import com.hmdp.interceptor.AdminAuthInterceptor;
import com.hmdp.service.AiDiagnosisClient;
import com.hmdp.service.IncidentContextBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V2-4 诊断接口契约测试：404 短路（不调用 Python）、三种诊断态透传、Java 降级仍 200、管理员令牌、Sentinel 限流。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentDiagnosisControllerTest {

    static {
        // Sentinel 首次加载时会把日志写到 <user.home>/logs/csp；本仓库只保证 workspace 可写，
        // 因此测试内把日志目录定向到 target/ 下（与手工启动应用时的 -Dcsp.sentinel.log.dir 等价）。
        System.setProperty("csp.sentinel.log.dir", "target/sentinel-logs");
        try {
            Files.createDirectories(Paths.get("target", "sentinel-logs"));
        } catch (IOException ignored) {
            // 目录创建失败时不影响断言
        }
    }

    private static final ObjectMapper JACKSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Mock
    private IncidentContextBuilder incidentContextBuilder;

    @Mock
    private AiDiagnosisClient aiDiagnosisClient;

    private MockMvc mockMvc;
    private List<FlowRule> originalRules;

    @BeforeEach
    void setUp() {
        originalRules = new ArrayList<>(FlowRuleManager.getRules());
        mockMvc = MockMvcBuilders.standaloneSetup(controller())
                .setControllerAdvice(new WebExceptionAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JACKSON))
                .build();
    }

    @AfterEach
    void tearDown() {
        // 限流规则是全局状态，必须还原，避免影响其它测试
        FlowRuleManager.loadRules(originalRules);
    }

    private IncidentDiagnosisController controller() {
        IncidentDiagnosisController controller = new IncidentDiagnosisController();
        ReflectionTestUtils.setField(controller, "incidentContextBuilder", incidentContextBuilder);
        ReflectionTestUtils.setField(controller, "aiDiagnosisClient", aiDiagnosisClient);
        return controller;
    }

    // ==================== 404 短路：不调用 Python ====================

    @Test
    void shouldReturn404WithoutCallingPythonWhenIncidentMissing() throws Exception {
        when(incidentContextBuilder.build(anyLong())).thenReturn(null);

        mockMvc.perform(get("/admin/incidents/999/diagnosis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("INCIDENT_NOT_FOUND"))
                .andExpect(jsonPath("$.incident_id").value(999));

        verify(aiDiagnosisClient, never()).diagnose(any());
        verify(aiDiagnosisClient, never()).rateLimited(any());
    }

    // ==================== 诊断结果透传 / 降级 ====================

    @Test
    void shouldReturnDiagnosedResult() throws Exception {
        when(incidentContextBuilder.build(4L)).thenReturn(context());
        when(aiDiagnosisClient.diagnose(any())).thenReturn(diagnosedResult());

        mockMvc.perform(get("/admin/incidents/4/diagnosis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosis_status").value("DIAGNOSED"))
                .andExpect(jsonPath("$.context_version").value("v2-2.1"))
                .andExpect(jsonPath("$.incident_id").value(9001))
                .andExpect(jsonPath("$.root_cause").value("券维度 Redis 库存采集中断"))
                .andExpect(jsonPath("$.evidence[0].path").value("incident.status"))
                .andExpect(jsonPath("$.evidence[0].observed").value("RESOLVED"))
                .andExpect(jsonPath("$.recommended_actions[0].requires_human").value(true))
                .andExpect(jsonPath("$.error_code").doesNotExist())
                .andExpect(jsonPath("$.evidence_validation.submitted").value(3))
                .andExpect(jsonPath("$.evidence_validation.accepted").value(1))
                .andExpect(jsonPath("$.evidence_validation.dropped").value(2))
                .andExpect(jsonPath("$.evidence_validation.over_limit").value(0))
                .andExpect(jsonPath("$.attempts").value(1));
    }

    @Test
    void shouldReturnInsufficientEvidence() throws Exception {
        when(incidentContextBuilder.build(4L)).thenReturn(context());
        AiDiagnosisResult result = new AiDiagnosisResult();
        result.setDiagnosisStatus(AiDiagnosisResult.STATUS_INSUFFICIENT_EVIDENCE);
        result.setContextVersion("v2-2.1");
        result.setIncidentId(9001L);
        result.setIncidentType("INVENTORY_MISMATCH");
        result.setInsufficientReason("缺少同时刻库存证据");
        result.setEvidenceValidation(new AiDiagnosisResult.EvidenceValidation(0, 0, 0, 0));
        result.setModel("deepseek-flash");
        result.setPromptVersion("v2-3.1");
        result.setDiagnosedAt(Instant.parse("2026-09-23T14:02:29.982545Z"));
        result.setElapsedMs(1200);
        result.setAttempts(1);
        when(aiDiagnosisClient.diagnose(any())).thenReturn(result);

        mockMvc.perform(get("/admin/incidents/4/diagnosis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosis_status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.insufficient_reason").value("缺少同时刻库存证据"))
                .andExpect(jsonPath("$.root_cause").doesNotExist());
    }

    @Test
    void shouldReturnPythonUnavailableAsHttp200() throws Exception {
        when(incidentContextBuilder.build(4L)).thenReturn(context());
        AiDiagnosisResult result = new AiDiagnosisResult();
        result.setDiagnosisStatus(AiDiagnosisResult.STATUS_UNAVAILABLE);
        result.setContextVersion("v2-2.1");
        result.setIncidentId(9001L);
        result.setIncidentType("INVENTORY_MISMATCH");
        result.setErrorCode("MODEL_NOT_CONFIGURED");
        result.setErrorOrigin(AiDiagnosisResult.ORIGIN_PYTHON);
        result.setEvidenceValidation(new AiDiagnosisResult.EvidenceValidation(0, 0, 0, 0));
        result.setAttempts(1);
        when(aiDiagnosisClient.diagnose(any())).thenReturn(result);

        mockMvc.perform(get("/admin/incidents/4/diagnosis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosis_status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.error_code").value("MODEL_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.error_origin").value("PYTHON"));
    }

    @Test
    void shouldReturnJavaDegradationAsHttp200() throws Exception {
        when(incidentContextBuilder.build(4L)).thenReturn(context());
        when(aiDiagnosisClient.diagnose(any())).thenReturn(AiDiagnosisResult.localUnavailable(
                "v2-2.1", 9001L, "INVENTORY_MISMATCH",
                "AI_SERVICE_UNREACHABLE", null, null, 2, 4012L));

        mockMvc.perform(get("/admin/incidents/4/diagnosis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosis_status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.error_code").value("AI_SERVICE_UNREACHABLE"))
                .andExpect(jsonPath("$.error_origin").value("JAVA_INTEGRATION"))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.elapsed_ms").value(4012))
                .andExpect(jsonPath("$.root_cause").doesNotExist())
                .andExpect(jsonPath("$.evidence").isEmpty())
                .andExpect(jsonPath("$.model").doesNotExist())
                .andExpect(jsonPath("$.prompt_version").doesNotExist())
                .andExpect(jsonPath("$.diagnosed_at").doesNotExist());
    }

    // ==================== Sentinel 限流 ====================

    @Test
    void shouldReturnRateLimitedWithoutCallingPythonWhenSentinelBlocks() throws Exception {
        FlowRule blockAll = new FlowRule(AiDiagnosisConfig.RESOURCE_INCIDENT_DIAGNOSIS);
        blockAll.setGrade(RuleConstant.FLOW_GRADE_QPS);
        blockAll.setCount(0);
        FlowRuleManager.loadRules(List.of(blockAll));

        when(incidentContextBuilder.build(4L)).thenReturn(context());
        when(aiDiagnosisClient.rateLimited(any())).thenReturn(AiDiagnosisResult.localUnavailable(
                "v2-2.1", 9001L, "INVENTORY_MISMATCH",
                "AI_RATE_LIMITED", null, null, 0, 0L));

        mockMvc.perform(get("/admin/incidents/4/diagnosis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosis_status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.error_code").value("AI_RATE_LIMITED"))
                .andExpect(jsonPath("$.error_origin").value("JAVA_INTEGRATION"))
                .andExpect(jsonPath("$.attempts").value(0));

        verify(aiDiagnosisClient, never()).diagnose(any());
        verify(aiDiagnosisClient).rateLimited(any());
    }

    // ==================== 管理员令牌（继承 /admin/incidents/** 规则） ====================

    @Test
    void shouldRequireAdminToken() throws Exception {
        MockMvc secured = MockMvcBuilders.standaloneSetup(controller())
                .setControllerAdvice(new WebExceptionAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JACKSON))
                .addInterceptors(new AdminAuthInterceptor("secret-token"))
                .build();

        secured.perform(get("/admin/incidents/4/diagnosis"))
                .andExpect(status().isForbidden());

        when(incidentContextBuilder.build(4L)).thenReturn(context());
        when(aiDiagnosisClient.diagnose(any())).thenReturn(diagnosedResult());

        secured.perform(get("/admin/incidents/4/diagnosis").header("X-Admin-Token", "secret-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diagnosis_status").value("DIAGNOSED"));
    }

    // ==================== helpers ====================

    private static IncidentContext context() {
        return new IncidentContext()
                .setContextVersion("v2-2.1")
                .setBuiltAt(Instant.parse("2026-09-23T14:00:00Z"))
                .setIncident(new IncidentContext.IncidentEvidence()
                        .setObservedAt(Instant.parse("2026-09-23T14:00:00Z"))
                        .setIncidentId(9001L)
                        .setIncidentType("INVENTORY_MISMATCH"));
    }

    private static AiDiagnosisResult diagnosedResult() {
        AiDiagnosisResult result = new AiDiagnosisResult();
        result.setDiagnosisStatus(AiDiagnosisResult.STATUS_DIAGNOSED);
        result.setContextVersion("v2-2.1");
        result.setIncidentId(9001L);
        result.setIncidentType("INVENTORY_MISMATCH");
        result.setRootCause("券维度 Redis 库存采集中断");
        result.setEvidence(List.of(new AiDiagnosisResult.Evidence()));
        result.getEvidence().get(0).setPath("incident.status");
        result.getEvidence().get(0).setObserved("RESOLVED");
        AiDiagnosisResult.RecommendedAction action = new AiDiagnosisResult.RecommendedAction();
        action.setAction("人工核对库存");
        action.setRationale("不自动覆盖");
        action.setRequiresHuman(true);
        result.setRecommendedActions(List.of(action));
        // submitted == accepted + dropped + over_limit 且 evidence.size() == accepted（与 V2-3.3 冻结不变量一致）
        result.setEvidenceValidation(new AiDiagnosisResult.EvidenceValidation(3, 1, 2, 0));
        result.setModel("deepseek-flash");
        result.setPromptVersion("v2-3.1");
        result.setDiagnosedAt(Instant.parse("2026-09-23T14:02:29.982545Z"));
        result.setElapsedMs(3625);
        result.setAttempts(1);
        return result;
    }
}
