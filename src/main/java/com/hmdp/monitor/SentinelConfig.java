package com.hmdp.monitor;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.hmdp.config.SeckillProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 规则：仅保护 AI/指标接口，秒杀接口不限流（脉冲流量由 Redis Lua 竞争）。
 *
 * @author zt
 * @version 5.0
 */
@Slf4j
@Configuration
public class SentinelConfig {

    public static final String RESOURCE_METRICS = "metrics";
    public static final String RESOURCE_AI_ANALYZE = "ai-analyze";

    @Resource
    private SeckillProperties seckillProperties;

    @PostConstruct
    public void init() {
        List<FlowRule> rules = new ArrayList<>();

        FlowRule metricsRule = new FlowRule();
        metricsRule.setResource(RESOURCE_METRICS);
        metricsRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        metricsRule.setCount(seckillProperties.getSentinel().getMetricsQps());
        rules.add(metricsRule);

        FlowRule aiRule = new FlowRule();
        aiRule.setResource(RESOURCE_AI_ANALYZE);
        aiRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        aiRule.setCount(seckillProperties.getSentinel().getAiAnalyzeQps());
        rules.add(aiRule);

        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 防刷规则加载: 指标 QPS={}, AI QPS={}",
                seckillProperties.getSentinel().getMetricsQps(),
                seckillProperties.getSentinel().getAiAnalyzeQps());
    }
}