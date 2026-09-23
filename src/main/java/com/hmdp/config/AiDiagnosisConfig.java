package com.hmdp.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * V2-4 AI 诊断调用的传输与限流配置。
 *
 * <p>
 * 1. {@code @Bean("aiDiagnosisRestTemplate")}：独立超时预算的 RestTemplate。
 * 刻意不复用 V2-1 的全局 {@code restTemplate}（3s/30s，服务于 DeepSeek 直连），
 * 因为 Python 侧模型超时为 40s，30s read 会造成「Java 先超时、Python 其实成功」的假失败。
 * 新客户端必须用 {@code @Qualifier} 显式注入本 bean，避免两个 RestTemplate 造成歧义。
 * </p>
 *
 * <p>
 * 2. Sentinel 诊断接口限流：复用既有 Sentinel 体系（不引入新框架）。
 * Sentinel 自身的 {@code FlowRuleManager.loadRules} 是**整体覆盖**语义，因此这里先合并既有规则再整体加载，
 * 并用 {@code @DependsOn("sentinelConfig")} 保证在 V2-1 的 {@code SentinelConfig} 之后执行，
 * 避免互相覆盖（否则 metrics / ai-analyze 规则或诊断规则会随初始化顺序丢失）。
 * </p>
 */
@Slf4j
@Configuration
@DependsOn("sentinelConfig")
public class AiDiagnosisConfig {

    /** 新 RestTemplate bean 名：客户端必须按名字注入 */
    public static final String REST_TEMPLATE_BEAN = "aiDiagnosisRestTemplate";

    /** Sentinel 资源名：诊断接口限流 */
    public static final String RESOURCE_INCIDENT_DIAGNOSIS = "incident-diagnosis";

    private final AiDiagnosisProperties properties;

    public AiDiagnosisConfig(AiDiagnosisProperties properties) {
        this.properties = properties;
    }

    @Bean(REST_TEMPLATE_BEAN)
    public RestTemplate aiDiagnosisRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        return new RestTemplate(factory);
    }

    @PostConstruct
    public void initDiagnosisFlowRule() {
        List<FlowRule> rules = new ArrayList<>(FlowRuleManager.getRules());
        rules.removeIf(rule -> RESOURCE_INCIDENT_DIAGNOSIS.equals(rule.getResource()));

        FlowRule diagnosisRule = new FlowRule(RESOURCE_INCIDENT_DIAGNOSIS);
        diagnosisRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        diagnosisRule.setCount(properties.getQps());
        rules.add(diagnosisRule);

        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 诊断接口限流规则加载: resource={}, QPS={}, 规则总数={}",
                RESOURCE_INCIDENT_DIAGNOSIS, properties.getQps(), rules.size());
    }
}
