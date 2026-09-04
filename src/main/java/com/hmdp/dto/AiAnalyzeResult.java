package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * @author zt
 * @version 1.0
 */
@Data
//多余字段自动忽略
@JsonIgnoreProperties(ignoreUnknown = true)
//自动把 snake_case 映射成 camelCase（小驼峰）
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AiAnalyzeResult {
    //问题原因
    private String reason;
    //优化建议
    private List<String> suggestion;
    //主状态（最终结论）
    private String primaryStatus;
    //次级状态（辅助判断）
    private List<String> secondaryStatuses;
    //关键症状
    private List<String> keySymptoms;
    //因果链
    private List<String> causalChains;
}
