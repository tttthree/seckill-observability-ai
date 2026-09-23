package com.hmdp.service;

import com.hmdp.dto.context.IncidentContext;

/**
 * Incident Context Builder（V2-2）。
 * <p>
 * 只读：不修改 Redis、数据库或 Incident 状态，不 ACK Stream，不做任何推断。
 */
public interface IncidentContextBuilder {

    /**
     * 围绕一个已存在的 Incident 构建上下文。
     *
     * @param incidentId Incident 主键
     * @return 构建结果；Incident 不存在时返回 null
     * @throws RuntimeException Incident 主证据（数据库行）读取失败时按既有运维查询语义直接抛出
     */
    IncidentContext build(long incidentId);
}
