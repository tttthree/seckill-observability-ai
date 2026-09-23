package com.hmdp.dto.diagnosis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hmdp.dto.context.IncidentContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2-4 诊断 JSON 契约测试（Java ↔ Python 双向）。
 *
 * <p>
 * 最有价值的一条：把 Python 侧已通过冻结契约校验的真实 fixture 反序列化进 Java 的
 * {@link IncidentContext}，再按 Java 实际会发送的形态重新序列化，逐字段比对
 * —— 保证 Java 送出的请求体不会造成契约漂移（Python 侧是 {@code extra="forbid"}）。
 * </p>
 */
class AiDiagnosisJsonContractTest {

    /** 模拟应用全局配置：spring.jackson.default-property-inclusion=non_null + JavaTimeModule */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private static final Path FROZEN_FIXTURE = Paths.get(
            "ai-diagnosis-service", "tests", "fixtures", "incident_context_v2_2_1.json");

    // ==================== 请求契约 ====================

    @Test
    void shouldWrapContextUnderSingleIncidentContextKey() throws Exception {
        IncidentContext context = new IncidentContext()
                .setContextVersion("v2-2.1")
                .setBuiltAt(Instant.parse("2026-09-23T14:00:00Z"));

        String json = objectMapper.writeValueAsString(
                new AiDiagnosisRequest().setIncidentContext(context));

        JsonNode root = objectMapper.readTree(json);
        assertEquals(Set.of("incident_context"), fieldNames(root));
        assertEquals("v2-2.1", root.get("incident_context").get("context_version").asText());
        // built_at 必须是 ISO-8601（JavaTimeModule 生效），而不是 epoch 数字
        assertTrue(root.get("incident_context").get("built_at").isTextual());
    }

    @Test
    void shouldSerializeFrozenFixtureWithoutContractDrift() throws Exception {
        assertTrue(Files.exists(FROZEN_FIXTURE), "冻结 fixture 必须存在: " + FROZEN_FIXTURE.toAbsolutePath());
        String fixtureJson = new String(Files.readAllBytes(FROZEN_FIXTURE), StandardCharsets.UTF_8);
        JsonNode expected = objectMapper.readTree(fixtureJson);

        IncidentContext context = objectMapper.readValue(fixtureJson, IncidentContext.class);
        String requestJson = objectMapper.writeValueAsString(
                new AiDiagnosisRequest().setIncidentContext(context));
        JsonNode request = objectMapper.readTree(requestJson);

        assertEquals(Set.of("incident_context"), fieldNames(request));
        // 类级 @JsonInclude(ALWAYS) 必须压过全局 non_null：未计划的数据源段仍需显式输出 null
        JsonNode contextNode = request.get("incident_context");
        assertTrue(contextNode.has("queue"), "未计划的数据源段必须显式输出 null");
        assertTrue(contextNode.get("queue").isNull());

        assertNoContractDrift(expected, request.get("incident_context"), "$");
    }

    // ==================== 响应契约（Java 本地降级形态） ====================

    @Test
    void shouldSerializeLocalDegradationWithExplicitNulls() throws Exception {
        AiDiagnosisResult result = AiDiagnosisResult.localUnavailable(
                "v2-2.1", 9001L, "INVENTORY_MISMATCH",
                "AI_SERVICE_UNREACHABLE", "INVALID_CONTEXT", 422, 2, 4012L);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(result));

        assertEquals("UNAVAILABLE", node.get("diagnosis_status").asText());
        assertEquals("AI_SERVICE_UNREACHABLE", node.get("error_code").asText());
        assertEquals("JAVA_INTEGRATION", node.get("error_origin").asText());
        assertEquals("INVALID_CONTEXT", node.get("python_error_code").asText());
        assertEquals(422, node.get("http_status").asInt());
        assertEquals(2, node.get("attempts").asInt());
        assertEquals(4012, node.get("elapsed_ms").asInt());
        assertTrue(node.get("evidence").isArray());
        assertTrue(node.get("evidence").isEmpty());
        assertTrue(node.get("recommended_actions").isEmpty());
        assertEquals(0, node.get("evidence_validation").get("submitted").asInt());
        assertEquals(0, node.get("evidence_validation").get("accepted").asInt());
        assertEquals(0, node.get("evidence_validation").get("dropped").asInt());
        assertEquals(0, node.get("evidence_validation").get("over_limit").asInt());

        // 不伪造 Python/模型侧信息：这些键必须存在且为 null（而非被 non_null 抹掉）
        for (String field : new String[]{"root_cause", "insufficient_reason", "model", "prompt_version",
                "diagnosed_at"}) {
            assertTrue(node.has(field), field + " 必须显式输出");
            assertTrue(node.get(field).isNull(), field + " 必须为 null");
        }
    }

    @Test
    void shouldNotEmitPythonContractKeysMissingFromRealResponse() throws Exception {
        // 真实 DIAGNOSED 响应样例（字段名/类型来自 Python V2-3 实际输出）
        String body = "{\"diagnosis_status\":\"DIAGNOSED\",\"context_version\":\"v2-2.1\","
                + "\"incident_id\":9001,\"incident_type\":\"INVENTORY_MISMATCH\","
                + "\"root_cause\":\"根因\",\"evidence\":[{\"path\":\"incident.status\","
                + "\"observed\":\"RESOLVED\",\"note\":null}],"
                + "\"recommended_actions\":[{\"action\":\"a\",\"rationale\":\"r\",\"requires_human\":true}],"
                + "\"insufficient_reason\":null,\"error_code\":null,"
                + "\"evidence_validation\":{\"submitted\":13,\"accepted\":10,\"dropped\":2,\"over_limit\":1},"
                + "\"model\":\"deepseek-flash\",\"prompt_version\":\"v2-3.1\","
                + "\"diagnosed_at\":\"2026-09-23T14:02:29.982545Z\",\"elapsed_ms\":3625}";

        AiDiagnosisResult result = objectMapper.readValue(body, AiDiagnosisResult.class);

        assertEquals(AiDiagnosisResult.STATUS_DIAGNOSED, result.getDiagnosisStatus());
        assertEquals(13, result.getEvidenceValidation().getSubmitted());
        assertEquals(10, result.getEvidenceValidation().getAccepted());
        assertEquals(2, result.getEvidenceValidation().getDropped());
        assertEquals(1, result.getEvidenceValidation().getOverLimit());
        assertEquals(Instant.parse("2026-09-23T14:02:29.982545Z"), result.getDiagnosedAt());
        assertEquals("RESOLVED", result.getEvidence().get(0).getObserved());
        assertEquals("deepseek-flash", result.getModel());
        assertEquals(3625, result.getElapsedMs());
        // Java 附加遥测字段在透传结果里为空，直到客户端填充
        assertNotNull(result.getEvidence());
        assertFalse(result.getEvidence().isEmpty());
    }

    // ==================== helpers ====================

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * 递归比对契约结构：字段集合必须完全一致（不允许缺失或多出），
     * 标量按类型比较，ISO-8601 文本按时刻比较（容忍 {@code .000Z} 之类的格式化差异）。
     */
    private static void assertNoContractDrift(JsonNode expected, JsonNode actual, String path) {
        if (expected.isObject()) {
            assertTrue(actual.isObject(), path + " 应为对象");
            Set<String> expectedFields = fieldNames(expected);
            Set<String> actualFields = fieldNames(actual);
            assertEquals(expectedFields, actualFields, path + " 字段集合发生契约漂移");
            for (String field : expectedFields) {
                assertNoContractDrift(expected.get(field), actual.get(field), path + "." + field);
            }
            return;
        }
        if (expected.isArray()) {
            assertTrue(actual.isArray(), path + " 应为数组");
            assertEquals(expected.size(), actual.size(), path + " 数组长度不一致");
            for (int i = 0; i < expected.size(); i++) {
                assertNoContractDrift(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
            return;
        }
        if (expected.isNull() || actual.isNull()) {
            assertEquals(expected.isNull(), actual.isNull(), path + " null 语义不一致");
            return;
        }
        if (expected.isNumber() && actual.isNumber()) {
            assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()), path + " 数值不一致");
            return;
        }
        if (expected.isTextual() && actual.isTextual()) {
            Optional<Instant> expectedInstant = parseInstant(expected.asText());
            Optional<Instant> actualInstant = parseInstant(actual.asText());
            if (expectedInstant.isPresent() && actualInstant.isPresent()) {
                assertEquals(expectedInstant.get(), actualInstant.get(), path + " 时刻不一致");
            } else {
                assertEquals(expected.asText(), actual.asText(), path + " 文本不一致");
            }
            return;
        }
        assertEquals(expected, actual, path + " 值不一致");
    }

    private static Optional<Instant> parseInstant(String value) {
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
