package com.hmdp.service.impl;

import com.hmdp.dto.AiAnalyzeResult;
import com.hmdp.monitor.DeepSeekConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAnalyzeServiceImplTest {

    @Test
    void shouldSkipExternalAiWhenThereIsNoRuntimeData() {
        AiAnalyzeServiceImpl service = new AiAnalyzeServiceImpl();

        AiAnalyzeResult result = service.analyze(Map.of(
                "runtime_metrics", Map.of("total_requests", 0)));

        assertEquals("UNKNOWN", result.getPrimaryStatus());
        assertTrue(result.getKeySymptoms().isEmpty());
        assertTrue(result.getCausalChains().isEmpty());
        assertTrue(result.getReason().contains("无压测数据"));
    }

    @Test
    void shouldSkipExternalAiWhenApiKeyIsBlank() {
        AiAnalyzeServiceImpl service = new AiAnalyzeServiceImpl();
        DeepSeekConfig config = new DeepSeekConfig();
        config.setApiKey(" ");
        config.setUrl("https://api.deepseek.com/chat/completions");
        ReflectionTestUtils.setField(service, "deepSeekConfig", config);

        AiAnalyzeResult result = service.analyze(Map.of(
                "runtime_metrics", Map.of("total_requests", 1)));

        assertEquals("UNKNOWN", result.getPrimaryStatus());
        assertTrue(result.getReason().contains("跳过外部 AI"));
    }
}
