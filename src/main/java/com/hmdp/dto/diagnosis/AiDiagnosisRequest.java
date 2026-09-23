package com.hmdp.dto.diagnosis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.hmdp.dto.context.IncidentContext;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * V2-4 Java → Python 请求包装：{@code {"incident_context": {...}}}。
 *
 * <p>
 * 直接复用 V2-2 冻结的 {@link IncidentContext}（不复制、不修改契约）；
 * Python 侧 {@code DiagnosisRequest} 是 {@code extra="forbid"}，
 * 因此这里**只允许**这一个字段，且 context 自身的 snake_case / ALWAYS 序列化语义保持不变。
 * </p>
 */
@Data
@Accessors(chain = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiDiagnosisRequest {

    private IncidentContext incidentContext;
}
