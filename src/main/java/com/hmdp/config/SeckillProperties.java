package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 秒杀业务配置（对应 application.yaml 中 seckill.* 段）
 * yaml 里没写的就保留 Java new 的默认值
 *
 * @author zt
 * @version 1.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "seckill")
public class SeckillProperties {

    /** 消费者配置 */
    private Consumer consumer = new Consumer();

    /** 重试配置 */
    private Retry retry = new Retry();

    /** 对账配置 */
    private Reconcile reconcile = new Reconcile();

    /** 压测模型配置（AI 诊断参考值） */
    private LoadModel loadModel = new LoadModel();

    /** Pending 独立处理器配置 */
    private PendingHandler pendingHandler = new PendingHandler();

    /** Sentinel 防刷配置（仅 AI/指标接口） */
    private Sentinel sentinel = new Sentinel();

    /** AI 诊断配置 */
    private Ai ai = new Ai();

    @Data
    public static class Consumer {
        private int threads = 3;
        private int batchSize = 20;
    }

    @Data
    public static class Retry {
        private int max = 3;
    }

    @Data
    public static class Reconcile {
        private long fixedDelayMs = 300000;
    }

    @Data
    public static class LoadModel {
        private int concurrency = 2000;
        private int stock = 400;
    }

    @Data
    public static class PendingHandler {
        /** 是否启用独立 Pending 处理器 */
        private boolean enabled = true;
        /** 定时扫描间隔（毫秒） */
        private long intervalMs = 5000;
        /** 每轮最多处理条数 */
        private int batchSize = 50;
        /** XCLAIM 认领时使用的消费者名 */
        private String consumerName = "pending-handler";
        /** 消息 Pending 未超此时间（毫秒）则跳过，给正常消费者 ACK 宽限期 */
        private long gracePeriodMs = 5000;
    }

    @Data
    public static class Sentinel {
        /** 指标接口 QPS */
        private int metricsQps = 20;
        /** AI 诊断接口 QPS */
        private int aiAnalyzeQps = 1;
    }

    @Data
    public static class Ai {
        /** 是否启用历史基线对比 */
        private boolean baselineEnabled = true;
        /** Redis 中保存基线的 Hash key */
        private String baselineKey = "seckill:metrics:baseline";
    }
}