package com.hmdp.controller;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.hmdp.dto.AiAnalyzeResult;
import com.hmdp.service.AiAnalyzeService;
import com.hmdp.service.MetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.Map;

/**
 * @author zt
 * @version 1.0
 */
@Slf4j
@RestController
@RequestMapping("/metrics")
public class MetricsController {

    @Resource
    private MetricsService metricsService;

    @Resource
    private AiAnalyzeService aiAnalyzeService;

    /**
     * 秒杀指标
     */
    @GetMapping("/seckill")
    public Map<String, Object> seckillMetrics() {
        Entry entry = null;
        try {
            // 告诉 Sentinel：有个请求要进"metrics"这个资源了
            entry = SphU.entry("metrics");
            return metricsService.getSeckillMetrics();
        } catch (BlockException e) {
            // 返回 error 提示
            return Map.of("error", "请求太频繁，请稍后再试");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    /**
     * 历史基线（供前端做趋势对比）
     */
    @GetMapping("/baseline")
    public Map<String, Object> baseline() {
        return metricsService.loadBaseline();
    }

    /**
     * AI分析入口
     */
    @GetMapping("/ai/analyze")
    public AiAnalyzeResult analyze() {
        Entry entry = null;
        try {
            entry = SphU.entry("ai-analyze");
            Map<String, Object> metrics = metricsService.getSeckillMetrics();
            return aiAnalyzeService.analyze(metrics);
        } catch (BlockException e) {
            // 返回一个 UNKNOWN 状态的兜底结果。
            AiAnalyzeResult fallback = new AiAnalyzeResult();
            fallback.setPrimaryStatus("UNKNOWN");
            fallback.setSecondaryStatuses(Collections.emptyList());
            fallback.setKeySymptoms(Collections.emptyList());
            fallback.setCausalChains(Collections.emptyList());
            fallback.setReason("AI分析请求被限流，请稍后再试（每 1 秒最多 1 次）");
            fallback.setSuggestion(Collections.emptyList());
            return fallback;
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }
}
