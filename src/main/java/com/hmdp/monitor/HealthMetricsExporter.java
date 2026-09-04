package com.hmdp.monitor;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Map;

/**
 * 将 ConsumerHealthIndicator 的健康状态导出为 Micrometer Gauge，
 * 使 Prometheus 可以在常规抓取周期内采集消费者健康数据。
 * <p>
 * 配合 alert.rules.yml + Alertmanager （通知人） 即可实现消费者心跳超时 / Pending 堆积的自动告警。
 *
 * @author zt
 * @version 3.1
 */
@Slf4j
@Component
public class HealthMetricsExporter {

    @Resource
    private MeterRegistry meterRegistry;

    /**
     * Spring Boot 自动发现所有 HealthIndicator 实现类，
     * 这里注入的是 ConsumerHealthIndicator
     */
    @Resource
    private HealthIndicator consumerHealthIndicator;

    @PostConstruct
    public void register() {
        // 1. 消费者健康状态：1 = UP, 0 = DOWN
        Gauge.builder("seckill_consumer_health", consumerHealthIndicator, h -> {
                    Health health = h.health();
                    return Status.UP.equals(health.getStatus()) ? 1 : 0;
                })
                .description("消费者线程健康状态: 1=UP, 0=DOWN（心跳超时/Pending堆积/线程未启动）")
                .register(meterRegistry);

        // 2. 心跳间隔（毫秒），-1 表示取不到
        Gauge.builder("seckill_consumer_heartbeat_age_ms", consumerHealthIndicator, h -> {
                    Health health = h.health();
                    Map<String, Object> details = health.getDetails();
                    Object age = details.get("heartbeat_age_ms");
                    if (age instanceof Number) {
                        return ((Number) age).doubleValue();
                    }
                    return -1.0;
                })
                .description("距上次消费者心跳的毫秒数")
                .register(meterRegistry);

        log.info("HealthMetricsExporter 注册完成: seckill_consumer_health + seckill_consumer_heartbeat_age_ms");

        // 3. 成功消费心跳间隔（毫秒），区分"活着"与"正常工作"
        Gauge.builder("seckill_consumer_success_heartbeat_age_ms", consumerHealthIndicator, h -> {
                    Health health = h.health();
                    Map<String, Object> details = health.getDetails();
                    Object age = details.get("success_heartbeat_age_ms");
                    if (age instanceof Number) {
                        return ((Number) age).doubleValue();
                    }
                    return -1.0;
                })
                .description("距上次成功消费 ACK 的毫秒数，超过 60s 表示消费卡住但线程未死")
                .register(meterRegistry);

        log.info("HealthMetricsExporter 追加注册: seckill_consumer_success_heartbeat_age_ms");
    }
}
