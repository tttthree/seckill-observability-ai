# hm-dianping 秒杀系统架构

## 1. 边界

项目采用 Spring Boot 2.7.18 和 JDK 11，只保留秒杀主线及其必要支撑：

- 最小用户登录与身份恢复
- 秒杀券创建和库存初始化
- Redis Lua 资格预占
- Redis Stream 异步落库
- Pending 认领、限次重试、死信和补偿
- Redis 与 MySQL 库存对账
- Micrometer、Prometheus、Grafana 监控
- DeepSeek 结构化运维诊断

非核心业务模块已经移除，源码包均服务于上述链路或其运行支撑。

## 2. 系统结构

```text
                         +--------------------+
                         | dashboard.html     |
                         +---------+----------+
                                   |
              +--------------------+--------------------+
              | Spring Boot 8081                        |
              |                                         |
              | User / Voucher / VoucherOrder API       |
              | Admin API / Metrics API / AI API        |
              +------+------------------+---------------+
                     |                  |
              +------v------+    +------v------+
              | Redis       |    | MySQL       |
              | login token |    | user        |
              | Lua stock   |    | voucher     |
              | order set   |    | stock       |
              | Stream/PEL  |    | order       |
              | metrics     |    +-------------+
              +-------------+
                     |
          +----------+-----------+
          | Micrometer / Actuator|
          +----------+-----------+
                     |
              +------v------+       +-------------+
              | Prometheus  +-------> Grafana     |
              +------+------+       +-------------+
                     |
              +------v------+
              | AI metrics  +-------> DeepSeek API
              | projection  |
              +-------------+
```

## 3. 请求到落库

```text
用户请求
  |
  | authorization token
  v
LoginInterceptor
  |
  v
VoucherOrderService.seckillVoucher
  |
  +--> total_requests + 1
  |
  v
seckill.lua
  |-- GET      seckill:stock:{voucherId}
  |-- SISMEMBER seckill:order:{voucherId} userId
  |-- INCRBY   stock -1
  |-- SADD     ordered user
  +-- XADD     stream.orders
  |
  +--> 0: reserve_success
  +--> 1: stock_fail_redis
  +--> 2: duplicate_request
  +--> null/error: reserve_error

Redis Stream consumer group g1
  |
  v
createVoucherOrder transaction
  |-- UPDATE tb_seckill_voucher SET stock = stock - 1
  |     WHERE voucher_id = ? AND stock > 0
  +-- INSERT tb_voucher_order
        UNIQUE(user_id, voucher_id)
```

HTTP 请求只等待 Redis 原子预占，不等待 MySQL 写入。客户端通过订单状态接口区分：

- `SUCCESS`：数据库订单已经存在
- `PROCESSING`：Redis 中已有资格，数据库尚未完成落库
- `NOT_FOUND`：Redis 和数据库均无下单记录

## 4. 失败恢复

### 4.1 幂等结果

数据库联合唯一索引触发的重复订单不会造成重复落单。消费者将其视为幂等结果并 ACK；数据库条件更新发现库存不足时仍保留 Pending，重试超限后统一执行原子补偿。

### 4.2 瞬时异常

数据库连接、事务或其他基础设施异常会让消息保持未 ACK 状态。独立 Pending 处理器定时：

1. 查询消费者组 PEL。
2. 对超过宽限时间的消息执行 `XCLAIM`。
3. 再次尝试事务落库。
4. 记录每条消息的重试次数并设置 TTL。

### 4.3 重试超限

`dead-letter.lua` 在 Redis 内原子完成：

1. `XACK` 原消息。
2. Redis 库存加一。
3. 删除用户已下单资格。
4. 写入死信 Stream。
5. 删除重试计数。

只有 `XACK` 成功时才执行补偿，避免一条消息被重复补偿。

### 4.4 死信重放

`replay-dead-letter.lua` 先校验库存和资格，再原子删除死信、重新扣减库存、恢复资格并投递主 Stream。重放过程不会绕过一人一单和库存约束。

## 5. 一致性边界

| 场景 | 防线 |
|---|---|
| Redis 并发预占 | 单个 Lua 脚本原子执行 |
| 同一用户重复请求 | Redis Set 快速拒绝 |
| 数据库重复落单 | `uk_user_voucher` 联合唯一索引 |
| 数据库超卖 | `stock > 0` 条件更新 |
| 消费进程异常 | Redis Stream PEL 与 `XCLAIM` |
| 重复补偿 | 补偿脚本以 `XACK` 结果作为执行门槛 |
| 长期库存偏差 | 脏券驱动的两阶段对账与人工告警 |

对账不会自动覆盖 Redis 或 MySQL 库存。因为消费中的短暂差异是正常状态，直接覆盖可能放大错误；系统只在连续两轮不一致时记录 `reconcile_mismatch` 并告警。

## 6. 监控模型

### 原始计数器

- `total_requests`
- `reserve_success`
- `reserve_error`
- `duplicate_request`
- `commit_success`
- `commit_error`
- `stock_fail_redis`
- `stock_fail_db`
- `consume_error`
- `reconcile_mismatch`

### 计算指标

- Redis Lua 预占成功率
- 库存竞争失败率
- 数据库提交率
- 请求到预占、预占到落库的漏斗转化率
- 提交损失率
- 基础设施失败率

消费者健康指标额外包含存活心跳、成功消费心跳、Pending 数量和死信数量，用于区分“没有消息”和“消费者活着但无法提交”。

## 7. AI 诊断

DeepSeek API Key 或地址未配置时，服务直接返回本地 `UNKNOWN` 降级结果，不向外部发送运行指标。

`MetricsServiceImpl` 将数据组织为：

```text
运行时计数
   + 理论负载模型
   + 系统能力比率
   + 业务结果
   + 链路漏斗
   + 消费者健康
   + 历史基线
          |
          v
结构化 JSON Prompt
          |
          v
DeepSeek
          |
          v
primary_status
key_symptoms
causal_chains
reason
suggestion
```

主状态包括：

- `NORMAL`：业务结果符合预期且基础设施正常
- `SATURATED`：达到库存或容量边界
- `DEGRADED`：预占到落库转化下降
- `INFRA_FAIL`：Redis、数据库或消费者出现明确异常
- `CRITICAL`：系统大面积失败或不可用
- `UNKNOWN`：指标不足、AI 服务不可用或证据不足

Prompt 强制要求每条结论引用输入指标，库存耗尽和重复下单被视为业务限制，而不是基础设施故障。

## 8. 数据库

数据库只包含四张表：

```text
tb_user
tb_voucher
tb_seckill_voucher
tb_voucher_order
```

初始化脚本位于 `src/main/resources/db/hmdp.sql`，不包含用户手机号或课程样例数据。

## 9. 关闭顺序

应用关闭时先停止 Pending 定时认领，再停止主消费者拉取，等待执行中的任务结束，最后更新消费者健康状态，减少消息处理中断窗口。
