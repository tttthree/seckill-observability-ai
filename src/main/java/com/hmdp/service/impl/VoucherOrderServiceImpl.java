package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.constant.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.interceptor.UserHolder;
import lombok.extern.slf4j.Slf4j;
import com.hmdp.config.SeckillProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.domain.Range;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import com.hmdp.monitor.ConsumerHealthIndicator;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import com.hmdp.exception.SeckillExceptions.*;

import static com.hmdp.constant.MetricsConstants.*;
import static com.hmdp.constant.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.constant.RedisConstants.STREAM_RETRY_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    @Lazy //为了避免循环依赖 使用@Lazy延迟注入
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ConsumerHealthIndicator healthIndicator;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // ==================== 配置（从 application.yaml 注入） ====================
    @Resource
    private SeckillProperties seckillProperties;

    // 多线程消费者（共享同一个消费者组，Redis Stream 自动负载均衡）
    private volatile boolean shuttingDown = false;
    private ExecutorService seckillOrderExecutor;

    // 独立 Pending 处理器（定时扫描 + XCLAIM，不阻塞主消费线程）
    private ScheduledExecutorService pendingHandlerExecutor;

    private static final String QUEUE_NAME = RedisConstants.STREAM_ORDERS_KEY;
    private static final String CONSUMER_GROUP = RedisConstants.STREAM_ORDERS_GROUP;
    private static final String DEAD_LETTER_QUEUE = RedisConstants.STREAM_ORDERS_DEAD_KEY;

    // 脏券集合
    private static final String RECONCILE_KEY = RedisConstants.SECKILL_VOUCHER_DIRTY_KEY;
    //首次不一致记录
    private static final String RECONCILE_MISMATCH_PREFIX = "seckill:reconcile:mismatch:";

    //项目启动后开启多个消费者线程
    @PostConstruct
    private void init() {
        // 初始化线程池
        seckillOrderExecutor = Executors.newFixedThreadPool(seckillProperties.getConsumer().getThreads(), r -> {
            Thread t = new Thread(r, "seckill-consumer");
            //声明是用户线程，默认就是false，只要有一个用户线程存活，JVM都不会退出
            t.setDaemon(false);
            return t;
        });

        try {
            stringRedisTemplate.opsForStream()
                    .add(QUEUE_NAME, Map.of("init", "1"));
            // 创建组：在 stream.orders 这个队列上创建消费者组 g1
            stringRedisTemplate.opsForStream().createGroup(
                    QUEUE_NAME,
                    ReadOffset.latest(),
                    CONSUMER_GROUP
            );
        } catch (Exception e) {
            // group 已存在，忽略
            log.info("stream或group已存在，忽略");
        }

        // 启动多个消费者线程
        for (int i = 0; i < seckillProperties.getConsumer().getThreads(); i++) {
            String consumerName = "c" + (i + 1);
            seckillOrderExecutor.submit(new VoucherOrderHandler(consumerName));
            log.info("消费者线程启动: {}", consumerName);
        }

        // 启动独立 Pending 处理器，不与正常消费抢占线程
        if (seckillProperties.getPendingHandler().isEnabled()) {
            // 创建单线程定时线程池：r = 线程要执行的任务，在这里就是 PendingHandlerTask
            pendingHandlerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                // 给线程起名 pending-handler，方便排查问题
                Thread t = new Thread(r, "pending-handler");
                // false = 用户线程，JVM 不会在任务跑一半时强制退出
                t.setDaemon(false);
                return t;
            });
            // 提交定时任务：马上开始，每 intervalMs 毫秒执行一轮扫描
            pendingHandlerExecutor.scheduleWithFixedDelay(
                    new PendingHandlerTask(),
                    0,
                    seckillProperties.getPendingHandler().getIntervalMs(),
                    TimeUnit.MILLISECONDS
            );
            log.info("Pending 处理器已启动: interval={}ms, batchSize={}",
                    seckillProperties.getPendingHandler().getIntervalMs(),
                    seckillProperties.getPendingHandler().getBatchSize());
        }

        healthIndicator.markAlive();
    }

    /**
     * 优雅关闭：收到关闭信号时停止消费新消息，等 Pending 处理完再退出  正常手动关闭时触发
     */
    @PreDestroy // Spring 容器销毁时自动调用
    private void shutdown() {
        shuttingDown = true;
        log.info("消费者线程正在关闭...");

        // 先停 Pending 处理器（不再认领新消息）
        if (pendingHandlerExecutor != null) {
            pendingHandlerExecutor.shutdown();
        }

        // 再停主消费线程
        seckillOrderExecutor.shutdown();
        try {
            if (!seckillOrderExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("消费者线程 10 秒内未完成，强制关闭");
                seckillOrderExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            //被 InterruptedException 中断的线程，中断标志会被自动清除
            seckillOrderExecutor.shutdownNow();
            Thread.currentThread().interrupt(); //把中断标志设回 true
        }

        // 主消费关完后再等 Pending 处理器（给 3 秒清理最后一轮，总共约 13 秒）
        if (pendingHandlerExecutor != null) {
            try {
                if (!pendingHandlerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    pendingHandlerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                pendingHandlerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        healthIndicator.markStopped();
        log.info("消费者线程已关闭");
    }

    //用户请求线程（生产者）当接收对应请求时才会启动
    @Override
    public Result seckillVoucher(Long voucherId) {

        //  入口埋点 记录总请求数
        incrMetric(M_TOTAL_REQUESTS);

        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        if (result == null) {
            incrMetric(M_RESERVE_ERROR);
            return Result.fail("Lua异常");
        }

        //2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            if (r == 1) {
                incrMetric(M_STOCK_FAIL_REDIS);
                return Result.fail("库存不足");
            } else {
                incrMetric(M_DUPLICATE_REQUEST);
                return Result.fail("不能重复下单");
            }
        }

        //Lua抢购资格成功
        incrMetric(M_RESERVE_SUCCESS);
        //4.返回订单id
        return Result.ok(orderId);
    }

    /**
     * 内部类
     * 消费者线程（支持多实例 + 批量消费 + 心跳上报）
     * 实现 Runnable 提交给线程池——线程池只接 Runnable 对象，不接普通方法调用
     */
    private class VoucherOrderHandler implements Runnable {

        private final String consumerName;

        //构造器 不是方法
        VoucherOrderHandler(String consumerName) {
            this.consumerName = consumerName;
        }

        @Override
        public void run() {
            Thread.currentThread().setName("seckill-consumer-" + consumerName);
            while (!shuttingDown && !Thread.currentThread().isInterrupted()) {
                // 心跳上报，放 try 外面避免心跳异常误入 Pending 处理
                healthIndicator.markAlive();
                //外层try catch 只会是read消息失败  read() 失败 → 消息未分配 → 留在 Stream → 下轮循环自动重读
                try {
                    // 1. 批量拉取新消息 XREADGROUP GROUP g1 {consumerName} COUNT {BATCH_SIZE} BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(CONSUMER_GROUP, consumerName),
                            StreamReadOptions.empty()
                                    .count(seckillProperties.getConsumer().getBatchSize())              // 一次拉取条数（可配置）
                                    .block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())
                    );
                    // 2. 无消息则继续下一轮
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    // 3. 逐条处理：handleVoucherOrder 内部已 catch 所有异常不会外抛
                    //    try-catch 只包 ACK，ACK 失败不拖累同批次其他记录
                    for (MapRecord<String, Object, Object> record : list) {
                        Map<Object, Object> values = record.getValue();

                        // 过滤初始化消息
                        if (values.containsKey("init")) {
                            try {
                                stringRedisTemplate.opsForStream()
                                        .acknowledge(QUEUE_NAME, CONSUMER_GROUP, record.getId());
                            } catch (Exception e) {
                                log.warn("init 消息 ACK 失败，忽略");
                            }
                            continue;
                        }

                        //4.下单
                        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                        handleVoucherOrder(voucherOrder);

                        try {
                            //5.ACK确认 SACK stream.orders g1 id
                            stringRedisTemplate.opsForStream()
                                    .acknowledge(QUEUE_NAME, CONSUMER_GROUP, record.getId());
                            // ACK 成功后上报成功消费心跳
                            healthIndicator.markSuccess();
                        } catch (Exception e) {
                            log.error("ACK 失败 messageId={}", record.getId(), e);
                            incrMetric(M_CONSUME_ERROR);
                            // 不在此处阻塞处理 Pending——独立 PendingHandler 会定时扫描并 XCLAIM 认领
                        }
                    }
                } catch (RedisSystemException e) {
                    if (e.getMessage() != null &&
                            e.getMessage().contains("Connection closed")) {
                        log.info("消费者线程 [{}] 已停止", consumerName);
                        return;
                    }
                    log.error("订单消费异常 [{}]", consumerName, e);
                    incrMetric(M_CONSUME_ERROR);
                } catch (Exception e) {
                    if (shuttingDown) {
                        log.info("消费者线程 [{}] 已停止", consumerName);
                        return;
                    }
                    log.error("订单消费异常 [{}]", consumerName, e);
                    incrMetric(M_CONSUME_ERROR);
                }
            }
            log.info("消费者线程 [{}] 已退出", consumerName);
        }
    }

    /**
     * 独立 Pending 处理器（定时扫描 + XCLAIM，不阻塞主消费线程）
     * <p>
     * XPENDING 扫描所有消费者名下未 ACK 的消息 → XCLAIM 认领到 pending-handler 名下
     * → 处理 → ACK 或移入死信队列
     */
    private class PendingHandlerTask implements Runnable {

        @Override
        public void run() {
            if (shuttingDown) return;

            try {
                healthIndicator.markAlive();
                int batchSize = seckillProperties.getPendingHandler().getBatchSize();

                // 1. XPENDING 获取所有未 ACK 消息（不限消费者）
                PendingMessages pendingMessages = stringRedisTemplate.opsForStream()
                        .pending(QUEUE_NAME, CONSUMER_GROUP, Range.unbounded(), batchSize);

                if (pendingMessages == null || pendingMessages.isEmpty()) {
                    return;
                }

                int processed = 0;
                for (PendingMessage msg : pendingMessages) {
                    if (shuttingDown) return;

                    String messageId = msg.getIdAsString();

                    // 2. XCLAIM 认领到 pending-handler 名下，跳过刚进入 Pending 的消息
                    List<MapRecord<String, Object, Object>> claimed =
                            stringRedisTemplate.opsForStream().claim(
                                    QUEUE_NAME, CONSUMER_GROUP,
                                    seckillProperties.getPendingHandler().getConsumerName(),
                                    Duration.ofMillis(seckillProperties.getPendingHandler().getGracePeriodMs()),
                                    RecordId.of(messageId)
                            );

                    if (claimed == null || claimed.isEmpty()) {
                        continue;  // 已被其他消费者 ACK 或未超过 min idle
                    }

                    MapRecord<String, Object, Object> record = claimed.get(0);
                    Map<Object, Object> values = record.getValue();

                    // 3. 过滤初始化消息
                    if (values.containsKey("init")) {
                        stringRedisTemplate.opsForStream()
                                .acknowledge(QUEUE_NAME, CONSUMER_GROUP, record.getId());
                        continue;
                    }

                    // 4. 查 DB 去重（避免重复事务）
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    if (orderExistsInDB(voucherOrder.getId())) {
                        stringRedisTemplate.opsForStream()
                                .acknowledge(QUEUE_NAME, CONSUMER_GROUP, record.getId());
                        continue;
                    }

                    // 5. 处理订单
                    handleVoucherOrder(voucherOrder);

                    // 6. ACK 或死信路由
                    try {
                        stringRedisTemplate.opsForStream()
                                .acknowledge(QUEUE_NAME, CONSUMER_GROUP, record.getId());
                        healthIndicator.markSuccess();
                    } catch (Exception e) {
                        log.error("PendingHandler ACK 失败 messageId={}", record.getId(), e);
                        // 超过重试次数 → 移入死信队列
                        if (exceedRetry(record.getId().getValue())) {
                            routeToDeadLetter(record);
                        }
                    }

                    processed++;
                }

                if (processed > 0) {
                    log.info("Pending 处理器本轮处理 {} 条消息", processed);
                }

            } catch (Exception e) {
                log.error("Pending 处理器异常", e);
            }
        }
    }

    /**
     * 计数器 +1，首次使用时设置 TTL（防止压测数据永久残留）
     */
    private void incrMetric(String key) {
        Long result = stringRedisTemplate.opsForValue().increment(key);
        // 首次创建 key 时设置过期时间
        if (result != null && result == 1) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(METRIC_TTL_SECONDS));
        }
    }


    /**
     * 查 DB 判断订单是否已存在（Pending 重试前使用，减少无效事务）
     */
    private boolean orderExistsInDB(Long orderId) {
        //SELECT COUNT(*) FROM voucher_order WHERE id = ?
        return lambdaQuery()
                .eq(VoucherOrder::getId, orderId)
                .count()
                > 0;
    }

    /**
     * 处理订单，事务成功后记录 M_COMMIT_SUCCESS 指标
     */
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        try {
            // 调用下单事务方法
            voucherOrderService.createVoucherOrder(voucherOrder);
            // 事务提交成功后埋点（Redis 原子计数器，Prometheus 通过 Gauge 采集）
            incrMetric(M_COMMIT_SUCCESS);
        }  catch (DuplicateKeyException e) {
            // 主键冲突（orderId 重复），极端并发下的防御性兜底；真正的"一人一单"由 Lua SISMEMBER 保证
            incrMetric(M_DUPLICATE_REQUEST);
            log.warn("主键冲突 orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(),
                    voucherOrder.getUserId(),
                    voucherOrder.getVoucherId(),
                    e);

        } catch (StockException e) {
            // 库存不足
            incrMetric(M_STOCK_FAIL_DB);
            log.error("库存扣减失败 voucherId={}", voucherOrder.getVoucherId(), e);

        } catch (Exception e) {
            // 其他异常
            incrMetric(M_COMMIT_ERROR);
            log.error("订单处理失败 orderId={}", voucherOrder.getId(), e);
        }
    }

    /**
     * 创建订单 不处理异常 全部抛出给调用方处理
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //1.扣减库存 乐观锁 CAS思想：在执行操作前再检查条件是否仍然成立
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1 ")//set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)//where voucher_id = ? and stock > 0
                .update();
        //2.库存不足
        if (!success) {
            // 扣减失败抛异常，由调用方捕获并埋点
            throw new StockException("库存不足");
        }

        // 标记该券发生变化，即脏券
        stringRedisTemplate.opsForSet()
                .add(RECONCILE_KEY, String.valueOf(voucherOrder.getVoucherId()));

        //3.保存订单，异常自然往上抛到 handleVoucherOrder 处理
        save(voucherOrder);
    }

    /**
     * 死信路由：库存补偿 + 移入死信队列 + ACK 原消息
     * <p>
     * 由 PendingHandlerTask 调用，保证死信处理逻辑一致
     */
    private void routeToDeadLetter(MapRecord<String, Object, Object> record) {
        log.error("消息超过最大重试次数，移入死信队列 messageId={}", record.getId());
        // 补偿：回退 Redis 库存（Lua 已扣减，但 DB 未扣）
        Map<Object, Object> deadValues = record.getValue();
        Object vid = deadValues.get("voucherId");
        if (vid != null) {
            stringRedisTemplate.opsForValue()
                    .increment(SECKILL_STOCK_KEY + vid);
            log.info("死信补偿：已回退 Redis 库存 voucherId={}", vid);
        }
        // 放入死信队列
        stringRedisTemplate.opsForStream().add(DEAD_LETTER_QUEUE, record.getValue());
        // ACK 原消息
        stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, CONSUMER_GROUP, record.getId());
    }

    /**
     * 是否超过最大重试次数
     */
    private boolean exceedRetry(String messageId) {
        String retryKey = STREAM_RETRY_KEY + messageId;
        Long retryCount = stringRedisTemplate.opsForValue().increment(retryKey);
        if (retryCount != null && retryCount == 1) {
            stringRedisTemplate.expire(retryKey, Duration.ofHours(1));
        }
        return retryCount != null && retryCount > seckillProperties.getRetry().getMax();
    }

    /**
     * 库存对账：对比 Redis 库存 vs DB 库存（脏券驱动，两阶段告警）
     */
    @Scheduled(fixedDelayString = "#{@seckillProperties.reconcile.fixedDelayMs}")
    public void reconcile() {
        try {
            Set<String> dirtyIds =
                    stringRedisTemplate.opsForSet()
                            .members(RECONCILE_KEY);
            if (dirtyIds == null || dirtyIds.isEmpty()) {
                return;
            }
            for (String idStr : dirtyIds) {
                Long voucherId = Long.valueOf(idStr);
                String redisKey = SECKILL_STOCK_KEY + voucherId;
                String redisStockStr = stringRedisTemplate.opsForValue().get(redisKey);
                int redisStock = redisStockStr == null ? 0 : Integer.parseInt(redisStockStr);
                SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
                if (voucher == null || voucher.getStock() == null) {
                    continue;
                }
                int dbStock = voucher.getStock();
                if (redisStock == dbStock) {
                    stringRedisTemplate.opsForSet()
                            .remove(RECONCILE_KEY, idStr);
                    stringRedisTemplate.delete(RECONCILE_MISMATCH_PREFIX + voucherId);
                    continue;
                }
                String mismatchKey = RECONCILE_MISMATCH_PREFIX + voucherId;
                String firstMismatchTime = stringRedisTemplate.opsForValue().get(mismatchKey);
                if (firstMismatchTime == null) {
                    stringRedisTemplate.opsForValue()
                            .set(mismatchKey, String.valueOf(System.currentTimeMillis()),
                                    Duration.ofMinutes(10));
                    log.warn("首次发现库存不一致 voucherId={}, redis={}, db={}",
                            voucherId, redisStock, dbStock);
                    continue;
                }
                log.error("库存持续不一致，需人工介入！voucherId={}, redis={}, db={}",
                        voucherId, redisStock, dbStock);
                stringRedisTemplate.opsForValue()
                        .set(mismatchKey, String.valueOf(System.currentTimeMillis()),
                                Duration.ofMinutes(10));
            }
        } catch (Exception e) {
            log.error("对账异常", e);
        }
    }

    // ==================== 已废弃：handlePendingList ====================
    // 原为消费者线程中 ACK 失败后的阻塞式 Pending 重试，已被 PendingHandlerTask 替代。
    // PendingHandlerTask 独立线程定时 XCLAIM，不阻塞主消费，吞吐更高。
    // 保留此方法供后续对照调试。
//    /**
//     * 处理pending-list
//     * MapRecord里有三个变量：stream id value
//     * value 里有 userId voucherId id
//     */
//    public void handlePendingList(String consumerName) {
//        while (!Thread.currentThread().isInterrupted()) {
//            healthIndicator.markAlive();
//            List<MapRecord<String, Object, Object>> list = null;
//            try {
//                list = stringRedisTemplate.opsForStream().read(
//                        Consumer.from(CONSUMER_GROUP, consumerName),
//                        StreamReadOptions.empty().count(1),
//                        StreamOffset.create(QUEUE_NAME, ReadOffset.from("0"))
//                );
//                if (list == null || list.isEmpty()) {
//                    break;
//                }
//                MapRecord<String, Object, Object> record = list.get(0);
//                Map<Object, Object> values = record.getValue();
//
//                if (values.containsKey("init")) {
//                    stringRedisTemplate.opsForStream()
//                            .acknowledge(QUEUE_NAME, CONSUMER_GROUP, record.getId());
//                    continue;
//                }
//
//                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//
//                if (orderExistsInDB(voucherOrder.getId())) {
//                    stringRedisTemplate.opsForStream()
//                            .acknowledge(QUEUE_NAME, CONSUMER_GROUP, record.getId());
//                    continue;
//                }
//                handleVoucherOrder(voucherOrder);
//                stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME, CONSUMER_GROUP, record.getId());
//            } catch (Exception e) {
//                log.error("处理pending-list订单异常", e);
//                try {
//                    if (list != null && !list.isEmpty()) {
//                        MapRecord<String, Object, Object> record = list.get(0);
//                        if (exceedRetry(record.getId().getValue())) {
//                            routeToDeadLetter(record);
//                        }
//                    }
//                    Thread.sleep(20);
//                    if (shuttingDown) return;
//                } catch (InterruptedException ex) {
//                    Thread.currentThread().interrupt();
//                    return;
//                }
//            }
//        }
//    }

}