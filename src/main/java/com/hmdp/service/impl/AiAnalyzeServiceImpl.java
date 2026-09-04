package com.hmdp.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.monitor.DeepSeekConfig;
import com.hmdp.dto.AiAnalyzeResult;
import com.hmdp.service.AiAnalyzeService;
import com.hmdp.config.SeckillProperties;
import com.hmdp.service.MetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.*;

/**
 * AI秒杀系统故障诊断服务
 * @author zt
 * @version 1.0
 */
@Slf4j
@Service
public class AiAnalyzeServiceImpl implements AiAnalyzeService {

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private DeepSeekConfig deepSeekConfig;

    @Resource
    private MetricsService metricsService;

    @Resource
    private SeckillProperties seckillProperties;

    /** 与 MetricsServiceImpl.getSeckillMetrics() 返回的 9 个 key 一一对应 */
    private static final List<String> INPUT_KEYS = List.of(
                     "context",
                     "load_model",
                     "runtime_metrics",
                     "expected_model",
                     "comparison",
                     "capacity_analysis",
                     "business_analysis",
                      "funnel_analysis",
                      "diagnosis"
    );

    /** 每个 section 的中文标签，仅用于 prompt 排版 */
    private static final List<String> INPUT_LABELS = List.of(
                    "运行环境",
                    "压测模型",
                    "运行时计数",
                    "期望值",
                    "偏差分析",
                    "系统能力层（比率）",
                    "业务结果层",
                    "链路转化层（漏斗）",
                    "特征工程预计算"
    );

    // ==================== 主流程 ====================

    @Override
    public AiAnalyzeResult analyze(Map<String, Object> input) {

        // 前置防御：指标全为零说明无压测数据，直接返回 UNKNOWN，省 API 调用费
        Object runtimeObj = input.get("runtime_metrics");
        if (runtimeObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> runtime = (Map<String, Object>) runtimeObj;
            Object totalObj = runtime.get("total_requests");
            double total = 0;
            if (totalObj instanceof Number) {
                total = ((Number) totalObj).doubleValue();
            }
            if (total == 0) {
                AiAnalyzeResult empty = new AiAnalyzeResult();
                empty.setPrimaryStatus("UNKNOWN");
                empty.setSecondaryStatuses(Collections.emptyList());
                empty.setKeySymptoms(Collections.emptyList());
                empty.setCausalChains(Collections.emptyList());
                empty.setReason("无压测数据或指标已重置（total_requests=0），无法进行有效诊断。" +
                        "请先运行一轮压测再触发 AI 分析。");
                empty.setSuggestion(List.of(
                        "先执行压测，确保 total_requests > 0",
                        "检查 POST /admin/metrics/reset 是否被误调用",
                        "确认 seckill:metrics:* 计数器在 Redis 中正常递增"
                ));
                return empty;
            }
        }

        try {
            // 加载历史基线（上次诊断的指标快照）
            Map<String, Object> baseline = metricsService.loadBaseline();

            //构造 prompt（含基线对比数据）
            String prompt = buildPrompt(input, baseline);
            String content = callDeepSeek(prompt);

            // 保存本次指标为下一轮基线
            if (seckillProperties.getAi().isBaselineEnabled()) {
                metricsService.saveBaseline(input);
            }

            return parse(content);

        } catch (Exception e) {

            log.error("AI analyze failed", e);
            return fallback(e);
        }
    }

    // ==================== Prompt 构造 ====================

    /**
     * 将 MetricsServiceImpl 产出的结构化 Map 拼接为 DeepSeek prompt。
     * 关键：使用 ObjectMapper 序列化每个 section → JSON，而非 Map.toString()。
     */
    private String buildPrompt(Map<String, Object> input, Map<String, Object> baseline) {
        StringBuilder promptBuilder = new StringBuilder(SYSTEM_PROMPT);

        for (int i = 0; i < INPUT_KEYS.size(); i++) {
            promptBuilder.append("\n--- ")
              .append(INPUT_LABELS.get(i))
              .append(" ---\n");
            promptBuilder.append(toJson(input.get(INPUT_KEYS.get(i))));
            promptBuilder.append("\n");
        }

        // 历史基线对比（当存在时）
        boolean hasBaseline = baseline != null
                && Boolean.TRUE.equals(baseline.get("exists"));
        if (hasBaseline) {
            promptBuilder.append("\n--- 历史基线对比 ---\n");
            promptBuilder.append("上次诊断时刻的指标快照（请逐一与当前指标对比，找出恶化或改善的信号）：\n");
            promptBuilder.append(toJson(baseline.get("snapshot")));
            promptBuilder.append("\n");
        }

        promptBuilder.append(ANALYSIS_RULES);
        return promptBuilder.toString();
    }

    // ==================== DeepSeek API 调用 ====================

    private String callDeepSeek(String prompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "deepseek-chat");
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", 0.1);
        requestBody.put("response_format", Map.of("type", "json_object"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(deepSeekConfig.getApiKey());

        HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(
                deepSeekConfig.getUrl(), httpEntity, String.class);

        if (!response.getStatusCode().is2xxSuccessful()
                || response.getBody() == null) {
            throw new RuntimeException("DeepSeek API returned non-2xx or empty body");
        }

        JsonNode responseJson;
        try {
            responseJson = objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DeepSeek response JSON", e);
        }

        //choices[0].message.content
        return responseJson.path("choices")
                .get(0)
                .path("message")
                .path("content")
                .asText("");
    }

    // ==================== JSON 解析 ====================

    private AiAnalyzeResult parse(String content) throws Exception {
        content = clean(content);
        return objectMapper.readValue(content, AiAnalyzeResult.class);
    }

    /**
     * 防御性清洗：去除 markdown 代码块和控制字符。
     * 即使 prompt 已要求纯 JSON，仍保留此逻辑兜底。
     */
    private String clean(String content) {
        if (content == null) {
            return "";
        }
        //去掉首尾空格和换行符
        content = content.trim();

        //去Markdown
        if (content.startsWith("```")) {
            content = content.replace("```json", "")
                    .replace("```", "")
                    .trim();
        }

        //substring() 的第二个参数是 左闭右开区间 [start, end+1）
        int start = content.indexOf("{");
        int end = content.lastIndexOf("}");
        if (start >= 0 && end > start) {
            content = content.substring(start, end + 1);
        }

        //删除所有不可见控制字符保留换行和Tab
        return content.replaceAll("[\\x00-\\x1F&&[^\\n\\t]]", "");
    }

    // ==================== 工具方法 ====================

    /** 安全序列化为 JSON，null 或异常时返回 "{}" */
    private String toJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            //Jackson 提供的对象转 JSON 方法
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    /** AI 调用失败时的兜底结果 */
    private AiAnalyzeResult fallback(Exception e) {
        AiAnalyzeResult fallbackResult = new AiAnalyzeResult();
        fallbackResult.setPrimaryStatus("UNKNOWN");
        //Collections.emptyList() 返回一个不可修改的空 List 即[]
        fallbackResult.setSecondaryStatuses(Collections.emptyList());
        fallbackResult.setKeySymptoms(Collections.emptyList());
        fallbackResult.setCausalChains(Collections.emptyList());
        fallbackResult.setReason("AI分析失败: " + e.getMessage());
        fallbackResult.setSuggestion(List.of(
                "检查 AI 服务状态",
                "检查网络连接",
                "使用规则引擎兜底"
        ));
        return fallbackResult;
    }

    // ==================== Prompt 模板 ====================

    private static final String SYSTEM_PROMPT =
            "你是秒杀系统智能运维诊断专家。\n" +
            "根据以下 JSON 格式的监控指标，推断系统运行状态、瓶颈位置、根因链路和优化方案。\n\n" +
            "数据说明：\n" +
            "- 比率字段为 0~1 的小数（如 0.85 表示 85%）\n" +
            "- 计数字段为绝对值\n" +
            "- expected_model 为压测前的理论期望值\n" +
            "- diagnosis 为预计算的特征信号，仅作参考，不可直接照搬为结论\n" +
            "- duplicate_request 为用户重复下单被拦截，属于合法的业务规则拒绝，不属于系统故障\n" +
            "- consume_fail 为消费者线程异常次数，属于基础设施异常信号\n" +
            "- consumer_alive 为消费者线程存活状态（1=正常，0=挂掉），0 时属于 INFRA_FAIL 级别故障\n" +
            "- heartbeat_age_ms 为距上次消费者心跳的毫秒数，超过 30000 表示心跳超时，消费者可能卡死或 GC 停顿\n";

    private static final String ANALYSIS_RULES =
            "====================\n" +
            "历史基线对比规则（当\"历史基线对比\"节存在时，本组规则优先级最高，覆盖以下所有规则）\n" +
            "====================\n\n" +
            "1. 第一段分析必须将当前指标与基线逐项对比，明确指出哪些指标恶化（>10%变化为显著）、哪些改善、哪些持平。\n" +
            "2. 当关键指标相比基线恶化>20%时，secondary_statuses 必须包含\"DEGRADING\"；改善>20%时包含\"IMPROVING\"。\n" +
            "3. 如果当前主状态与上次一致但指标全面恶化，原因分析必须解释\"为什么同样的状态但表现更差\"。\n" +
            "4. 基线数据中的 timestamp 是上次诊断时间，可在 reason 中引用为\"相比[时间点]的上一轮诊断\"。\n\n" +
            "====================\n" +
            "证据约束\n" +
            "====================\n\n" +
            "1. 所有结论必须基于输入数据中的明确指标，禁止凭空推测或引入外部知识。\n" +
            "2. 库存耗尽和重复下单拦截（stock_fail > 0 或 duplicate_request > 0）属于业务限制，不属于系统故障。\n" +
            "3. 成功率下降如可被库存耗尽解释，优先归因为 SATURATED，而非基础设施问题。\n" +
            "4. 多个解释同时成立时，选择证据最充分、假设最少的解释。\n" +
            "5. 证据不足时输出 UNKNOWN，禁止编造原因。\n\n" +
            "====================\n" +
            "主状态判定规则\n" +
            "====================\n\n" +
            "NORMAL    — 业务结果接近预期（order_success ≈ expected_success），基础设施无异常（infra_fail_rate ≈ 0）。\n" +
            "SATURATED — 达到库存/容量边界，存在业务拒绝（stock_fail > 0），但基础设施正常。\n" +
            "DEGRADED  — 业务成功率明显低于预期，且存在性能下降信号（commit_drop_rate 偏高）。\n" +
            "INFRA_FAIL — Redis/DB/Consumer 存在明确故障证据（redis_fail > 0 或 db_fail > 0 或 consumer_alive = 0 或 heartbeat_age_ms > 30000，infra_fail_rate 偏高）。\n" +
            "CRITICAL  — 系统不可用或大面积失败（order_success_rate 极低，infra_fail_rate 极高）。\n\n" +
            "====================\n" +
            "key_symptoms 筛选规则（必须遵守）\n" +
            "====================\n\n" +
            "1. 只列出对主状态判定有决定性作用的症状，2~4 个即可，不要列全部指标。\n" +
            "2. 症状之间必须互斥：禁止同时列出两个本质上描述同一现象的症状（如\"成功率20%\"和\"Lua成功率20%\"选其一）。\n" +
            "3. 正常指标和预期内的业务行为不算症状：infra_fail_rate=0、duplicate_request=64（正常拦截）不放入 key_symptoms。\n" +
            "4. 每条必须引用具体数值（如\"订单成功率仅 20.0%（400/2000）\"），禁止模糊措辞。\n" +
            "5. 中文自然语言描述，禁止出现字段名、snake_case。\n\n" +
            "====================\n" +
            "causal_chains 归因规则（必须遵守）\n" +
            "====================\n\n" +
            "1. 1~3 条因果链，每条代表不同的归因路径，禁止多条链指向同一个根因。\n" +
            "2. 每条格式：根本原因 → 中间机制 → 最终表现。必须引用具体数值证明每一步。\n" +
            "3. 区分\"根因\"和\"现象\"：库存不足是现象，根因要回答\"为什么库存不足\"（库存配置太少？流量超预期？）。\n" +
            "4. 反例：禁止同时出现\"库存只有400→库存耗尽\"和\"2000并发远超400→库存耗尽\"，这两条根因相同，合并为一条。\n\n" +
            "====================\n" +
            "reason 写作要求（必须遵守）\n" +
            "====================\n\n" +
            "1. 不少于 120 字，按以下结构组织：\n" +
            "   第一段（2-3句）：当前主状态是什么，为什么是这个状态而非其他可能状态（如为什么是 SATURATED 而非 INFRA_FAIL）。\n" +
            "   第二段（2-3句）：数据如何支撑这个结论，引用关键对比（实际 vs 期望、各层转化率差异）。\n" +
            "   第三段（1-2句）：影响范围和风险评估。\n" +
            "2. 禁止逐条罗列数据——读者能看到面板，需要的是解读和判断，不是复读。\n" +
            "3. 所有数值引用必须来自输入数据，禁止模糊描述。\n\n" +
            "====================\n" +
            "suggestion 建议要求（必须遵守）\n" +
            "====================\n\n" +
            "1. 2~4 条，每条必须可直接执行，禁止泛泛而谈（如\"优化系统\"）。\n" +
            "2. 尽量量化：不说\"增加库存\"，说\"建议库存从400提升至800，预期成功率从20%提升至40%\"。\n" +
            "3. 按优先级排序：第一条解决核心瓶颈，后续条解决次要问题或预防复发。\n" +
            "4. 无异常的组件禁止提建议（如 infra_fail_rate=0，就不要建议\"检查Redis\"）。\n\n" +
            "====================\n" +
            "输出格式\n" +
            "====================\n\n" +
            "仅返回一个合法 JSON 对象，禁止 markdown、代码块、解释文字。\n\n" +
            "示例（仅供参考 JSON 结构和措辞风格，方括号内容必须替换为上方输入数据中的实际数值）：\n" +
            "{\n" +
            "  \"primary_status\": \"SATURATED\",\n" +
            "  \"secondary_statuses\": [],\n" +
            "  \"key_symptoms\": [\n" +
            "    \"库存竞争失败率 [根据stock_fail_rate计算]%，[stock_fail] 个请求因库存不足被拒\",\n" +
            "    \"订单成功率仅 [根据order_success_rate计算]%（[order_success]/[total_requests]）\"\n" +
            "  ],\n" +
            "  \"causal_chains\": [\n" +
            "    \"根因描述 → 中间机制（引用具体数值） → 最终表现\"\n" +
            "  ],\n" +
            "  \"reason\": \"第一段：当前主状态是什么，为什么是这个状态而非其他可能状态。第二段：数据如何支撑，引用实际vs期望的对比。第三段：影响范围和风险。禁止引用示例中的任何数值。\",\n" +
            "  \"suggestion\": [\n" +
            "    \"第一条：量化解决核心瓶颈（如扩容从X到Y，预期效果从A%到B%）\",\n" +
            "    \"第二条：若瓶颈不可消解，提供替代方案（如限流、排队）\",\n" +
            "    \"第三条：预防性建议（仅当输入数据中存在相关风险信号时）\"\n" +
            "  ]\n" +
            "}\n";
}
