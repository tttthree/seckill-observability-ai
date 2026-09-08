package com.hmdp.service;

import java.util.Map;

/**
 */
public interface MetricsService {
    Map<String, Object> getSeckillMetrics();

    /** 保存当前指标为历史基线（供下次 AI 诊断对比） */
    void saveBaseline(Map<String, Object> metrics);

    /** 加载上次保存的历史基线 */
    Map<String, Object> loadBaseline();
}
