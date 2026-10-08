package com.hmdp.controller;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.hmdp.service.MetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 秒杀运行指标接口。
 * <p>
 * 只暴露运行计数快照，不做任何 AI 诊断——诊断入口是
 * {@code GET /admin/incidents/{id}/diagnosis}（Incident → Context → AI Diagnosis Service）。
 */
@Slf4j
@RestController
@RequestMapping("/metrics")
public class MetricsController {

    @Resource
    private MetricsService metricsService;

    /**
     * 秒杀运行指标
     */
    @GetMapping("/seckill")
    public Map<String, Object> seckillMetrics() {
        Entry entry = null;
        try {
            entry = SphU.entry("metrics");
            return metricsService.getSeckillMetrics();
        } catch (BlockException e) {
            return Map.of("error", "请求太频繁，请稍后再试");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }
}
