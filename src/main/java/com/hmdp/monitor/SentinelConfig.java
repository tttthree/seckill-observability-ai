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
 * Sentinel 规则：仅保护指标接口，秒杀接口不限流（脉冲流量由 Redis Lua 竞争）。
 * <p>
 * Incident 诊断接口的限流规则不在这里：它由
 * {@link com.hmdp.config.AiDiagnosisConfig} 以合并既有规则的方式单独加载。
 *
 */
@Slf4j
@Configuration
public class SentinelConfig {

    public static final String RESOURCE_METRICS = "metrics";

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

        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 防刷规则加载: 指标 QPS={}", seckillProperties.getSentinel().getMetricsQps());
    }
}
