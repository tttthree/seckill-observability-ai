package com.hmdp.service;

import java.util.Map;

/**
 * 秒杀运行指标采集。
 * <p>
 * 只投影真实运行计数器与消费者健康状态，供运维监控页 / Prometheus 使用；
 * 不做任何诊断推断（诊断由 Incident → Context → AI Diagnosis Service 负责）。
 */
public interface MetricsService {

    /** 当前运行指标快照（顶层只有 {@code runtime_metrics}） */
    Map<String, Object> getSeckillMetrics();
}
