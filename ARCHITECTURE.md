# Seckill Observability & AI Diagnosis 架构

## 1. 边界

项目采用 Spring Boot 2.7.18 和 JDK 11，只保留秒杀主线及其必要支撑：

- 最小用户登录与身份恢复
- 秒杀券创建和库存初始化
- Redis Lua 资格预占
- Redis Stream 异步落库
- Pending 认领、限次重试、死信和补偿
- Redis 与 MySQL 库存对账
- 统一故障事件（Incident）层
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

## 7. 故障事件层（Incident）

不同来源的异常在秒杀链路中被分别发现，原本只落到日志和 Redis 计数器，无法回答"这个故障什么时候开始、发生过几次、是否恢复"。V2-1 引入统一故障事件层：

```text
对账偏差 / 死信 / 消费者不健康
        |
        v
IncidentReport（类型 + businessKey + 级别 + 证据）
        |
        v
IncidentService：OPEN 聚合 / RESOLVE 关闭
        |
        v
tb_incident → GET /admin/incidents
```

### 7.1 当前接入的故障来源

| 类型 | 检测来源 | 恢复依据 | 级别 |
|---|---|---|---|
| `INVENTORY_MISMATCH` | `reconcile()` 两阶段确认后的持续偏差 | 同一轮对账发现 Redis 库存与 DB 库存重新一致 | HIGH |
| `DEAD_LETTER` | `routeToDeadLetter()` 中补偿脚本返回成功 | 死信 Stream 中已无该券记录（重放后的真实状态） | HIGH |
| `CONSUMER_UNHEALTHY` | `ConsumerHealthIndicator.health()` 的 DOWN / DEGRADED | 健康检查恢复 UP 且 `consumer_status=HEALTHY` | CRITICAL / MEDIUM |

未接入：`commit_error`、`consume_error`、`reserve_error`、`stock_fail_db` 等只有累计计数、没有业务键与阈值语义的瞬时信号。按单次事件建 Incident 会产生噪声，本阶段不做。

注意：消费者心跳超时（30s）、Pending 堆积、消费停滞等阈值判断全部保留在 `ConsumerHealthIndicator` 内；`IncidentDetector` 只读取其判定结果并驱动事件状态，不复制阈值。

### 7.2 去重与聚合

```text
open_key = incident_type + ':' + business_key   （OPEN 时写入，RESOLVED 时置 NULL）
UNIQUE KEY uk_incident_open (open_key)
```

上报流程：

1. 原子聚合更新：`occurrence_count = occurrence_count + 1`、刷新 `last_detected_at` 与 `snapshot`；
2. 影响行数为 0 才插入新事件；并发撞唯一索引时回退为步骤 1；
3. 级别升级由单条条件 UPDATE 完成：`WHERE ... AND CASE severity WHEN 'LOW' THEN 1 ... END < 本次级别序号`，不读取当前级别再比较，任意并发顺序都不会降级；
4. 恢复时置 `RESOLVED` + `resolved_at`，并把 `open_key` 置 NULL。

这样同一故障持续存在时只有一条 OPEN 记录（`occurrence_count` 递增），恢复后历史保留，复发再生成新事件。去重保证放在数据库唯一索引上，因此多消费者线程、多实例并发扫描也不会重复建单。

写入与检测链路保持 fail-open：`report()` / `resolve()` / `listOpenIncidents()` 吞掉异常并只记录日志，因为数据库本身可能就是故障源，故障记录失败不得影响秒杀主链路。但**运维查询必须显式失败**：`listIncidents()` / `getIncident()` 不吞异常，由 `WebExceptionAdvice` 统一返回 `success=false`，避免把数据库故障伪装成"没有故障"。主键使用数据库自增而非 Redis 生成，避免 Redis 不可用时无法记录故障。

故障事件包含业务键、关联券 id 与库存快照，`GET /admin/incidents/**` 因此与写操作一样要求 `X-Admin-Token`；其余 `/admin/**` 只读接口保持原有免令牌行为。

## 8. Incident Context Builder（V2-2）

围绕一个已存在的 Incident 收集真实运行证据，输出统一的 `IncidentContext`，作为后续 AI Diagnosis Service 的 Java→Python 输入契约。**本层只做 Context Building，不做诊断**：不含根因推断、不含置信度评分、不调 LLM。

### 8.1 采集范围按 IncidentType 严格计划

| IncidentType | planned_sources |
|---|---|
| `INVENTORY_MISMATCH` | incident + metrics + redis(券) + database(券) |
| `DEAD_LETTER` | incident + metrics + redis(券) + database(券) + queue + consumer_health |
| `CONSUMER_UNHEALTHY` | incident + metrics + queue + consumer_health + runtime |

未计划的数据源既不采集、也不进入 `available/unavailable` 列表，其 JSON 段为 `null`。

### 8.2 契约与语义约束

- `built_at` 与各段 `observed_at` 为 UTC `Instant`；Incident 段时间字段原样输出数据库中的无时区 `LocalDateTime`；`snapshot.detected_at` 保留 epoch millis。
- 检测证据（`incident.*`，含 `detected_snapshot`，scope 固定 `LATEST_DETECTION`）与构建时刻状态（`redis/database/queue/consumer_health/metrics/runtime`）严格分离。
- **absent ≠ 0**：Redis 标量带 `present` 标志，`present=false` 时 `value=null`；`metrics.counter_presence` 让"计数器缺省按 0 参与计算"这一既有约定在契约层可见。
- `metrics` 只投影 10 个真实运行计数器与 presence，不含 benchmark context / load_model / expected_model / comparison，也不重复 consumer health 字段。
- `snapshot` 按 IncidentType 白名单投影；`detected_snapshot`、`recent_orders` 均不含 `user_id`。
- 所有集合读取有界：`recent_orders` ≤20、`recent_previous_incidents` ≤5（排除当前 Incident）、死信从最新端读 ≤50 条，达到上限记 `truncations`。
- 契约所有嵌套 DTO 都声明 `@JsonInclude(ALWAYS)`，保证 nullable 字段真实序列化为 `null`。

### 8.3 Partial failure 与质量描述

每个数据源独立 try/catch：失败只把该段置 `null`、记入 `unavailable_sources` 与 `errors`（仅 `source` / `error_type` / 通用 message，原始异常只进服务日志），其余数据源正常返回。`context_quality.complete` 只相对于本 Incident 的 `planned_sources` 判断。`logs` 当前无结构化日志源，列为 `not_implemented_sources`，不影响 `complete`。

`GET /admin/incidents/{id}/context`：Incident 存在返回 200（可为 partial）；不存在返回 404 + `INCIDENT_NOT_FOUND`；主证据（Incident 行）读取异常按既有运维查询语义抛出。实时构建、不落库、全程只读。

### 8.4 契约中刻意不提供的字段

`queue.consumer_group` 只包含 `name / consumers_total / pending_total / last_delivered_id`，**不提供 `lag` 与 `entries_read`**：spring-data-redis 2.7.18 的 `XInfoGroup` 未暴露这两个字段，而 `RedisConnection.execute` 在 Lettuce 下使用 `ByteArrayOutput`，无法解码含整数的嵌套数组回复（实测 `UnsupportedOperationException`）。既然无法在真实环境稳定取得，就不把永久为 `null` 的字段冻结进 V2-3 契约；该说明同时写入 `context_quality.notes`，避免下游误以为数据缺失。

## 9. AI 诊断

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

## 10. 数据库

数据库包含五张表：

```text
tb_user
tb_voucher
tb_seckill_voucher
tb_voucher_order
tb_incident
```

初始化脚本位于 `src/main/resources/db/hmdp.sql`，不包含用户手机号或课程样例数据。既有环境升级故障事件表执行 `src/main/resources/db/incident-migration.sql`（幂等）。

## 11. 关闭顺序

应用关闭时先停止 Pending 定时认领，再停止主消费者拉取，等待执行中的任务结束，最后更新消费者健康状态，减少消息处理中断窗口。
