package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * V2-4 Java → Python AI Diagnosis 调用配置（对应 application.yaml 中 ai-diagnosis.* 段）。
 *
 * <p>
 * 有意独立于 V2-1 冻结的 {@link SeckillProperties}；所有项都自带默认值，
 * 因此本地未显式配置（application.yaml 为 gitignored 的本机文件）也能按默认值运行。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-diagnosis")
public class AiDiagnosisProperties {

    /** 是否启用外部 AI 诊断；false 时客户端直接本地降级，不发起任何 HTTP 调用 */
    private boolean enabled = true;

    /** Python V2-3 诊断服务端点 */
    private String url = "http://127.0.0.1:8000/api/v1/diagnosis";

    /** 连接超时（毫秒）：超时属于可重试失败 */
    private int connectTimeoutMs = 2000;

    /** 读取超时（毫秒）：必须大于 Python 侧 deepseek_timeout_seconds(40s)；读取超时**不重试** */
    private int readTimeoutMs = 45000;

    /** 最多尝试次数（1 次调用 + 最多 1 次额外重试 = 2） */
    private int maxAttempts = 2;

    /** 诊断接口 Sentinel 限流 QPS（复用仓库既有 Sentinel，不引入新框架） */
    private int qps = 1;
}
