package com.hmdp.service;

import com.hmdp.dto.AiAnalyzeResult;

import java.util.List;
import java.util.Map;

/**
 * @author zt
 * @version 1.0
 */
public interface AiAnalyzeService {
    AiAnalyzeResult analyze(Map<String, Object> input);
}
