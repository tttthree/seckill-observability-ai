package com.hmdp.service;

import com.hmdp.dto.context.IncidentContext;
import com.hmdp.dto.diagnosis.AiDiagnosisResult;

/**
 * V2-4 Java → Python 结构化诊断客户端。
 *
 * <p>
 * 契约：
 * </p>
 * <ul>
 *   <li><b>永不抛出</b>：任何传输/解析/语义失败都返回 {@code diagnosis_status=UNAVAILABLE} 的本地降级结果，
 *       由 {@code error_code} + {@code error_origin} 说明原因，绝不伪造 {@code DIAGNOSED}；</li>
 *   <li><b>有界重试</b>：仅 connect timeout / connection refused / unreachable / HTTP 502/503/504
 *       允许最多额外重试 1 次；read timeout、HTTP 500、4xx、malformed 200、Python 200+UNAVAILABLE 一律不重试；</li>
 *   <li>只读：不修改 Incident / Redis / MySQL，不执行任何运维动作。</li>
 * </ul>
 */
public interface AiDiagnosisClient {

    /**
     * 对已构建好的 IncidentContext 请求结构化诊断。
     *
     * @return Python 结果（类型化透传）或 Java 本地降级结果，永不为 null、永不抛异常
     */
    AiDiagnosisResult diagnose(IncidentContext context);

    /**
     * Sentinel 限流命中时的本地降级结果：{@code error_code=AI_RATE_LIMITED}、{@code attempts=0}，
     * **不会发起任何 HTTP 调用**。
     */
    AiDiagnosisResult rateLimited(IncidentContext context);
}
