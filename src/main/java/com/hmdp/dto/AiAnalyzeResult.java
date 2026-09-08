package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AiAnalyzeResult {
    private String reason;
    private List<String> suggestion;
    private String primaryStatus;
    private List<String> secondaryStatuses;
    private List<String> keySymptoms;
    private List<String> causalChains;
}
