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
- 所有集合读取有界：`recent_orders` ≤20、`recent_previous_incidents` ≤5（排除当前 Incident）、死信从最新端读 ≤50 条。截断判定采用 N+1 探测：只有确实多出第 N+1 条才记 `truncations`，对外最多返回 N 条，避免"刚好等于上限"被误报为截断。
- 死信过滤与 V2-1 的券级 Incident 聚合语义一致：`related_voucher_id` 存在时只按 `voucherId` 匹配（同一张券的多个不同 `orderId` 都属于同一故障事件）；`snapshot.order_id` 仅代表"最近一次检测证据"，只在没有券维度时作为 fallback 过滤条件。
- 契约所有嵌套 DTO 都声明 `@JsonInclude(ALWAYS)`，保证 nullable 字段真实序列化为 `null`。

### 8.3 Partial failure 与质量描述

每个数据源独立 try/catch：失败只把该段置 `null`、记入 `unavailable_sources` 与 `errors`（仅 `source` / `error_type` / 通用 message，原始异常只进服务日志），其余数据源正常返回。`available_sources` 只由**成功完成**的采集写入（构建失败的数据源不会出现在其中）。子读取失败（例如 `queue` 段内的消费者组读取）只记 `errors` 并保留该段其它已成功数据，不清空整段，但同样使 `complete=false`。`context_quality.complete` 只相对于本 Incident 的 `planned_sources` 判断。`logs` 当前无结构化日志源，列为 `not_implemented_sources`，不影响 `complete`。

`GET /admin/incidents/{id}/context`：Incident 存在返回 200（可为 partial）；不存在返回 404 + `INCIDENT_NOT_FOUND`；主证据（Incident 行）读取异常按既有运维查询语义抛出。实时构建、不落库、全程只读。

### 8.4 契约中刻意不提供的字段

`queue.consumer_group` 只包含 `name / consumers_total / pending_total / last_delivered_id`，**不提供 `lag` 与 `entries_read`**：spring-data-redis 2.7.18 的 `XInfoGroup` 未暴露这两个字段，而 `RedisConnection.execute` 在 Lettuce 下使用 `ByteArrayOutput`，无法解码含整数的嵌套数组回复（实测 `UnsupportedOperationException`）。既然无法在真实环境稳定取得，就不把永久为 `null` 的字段冻结进 V2-3 契约；该说明同时写入 `context_quality.notes`，避免下游误以为数据缺失。

## 9. AI 诊断（基于全局指标）

Java 侧原有的指标诊断链路（`GET /metrics/ai/analyze`，保持不变）：

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

## 10. AI 诊断（V2-3 结构化诊断服务）

Java 侧原有的 `GET /metrics/ai/analyze`（`AiAnalyzeServiceImpl`）是基于**全局指标**的诊断，保持不变；V2-3 新增一条独立的、基于**单个 IncidentContext** 的诊断链路：

```text
V2-2 IncidentContextBuilder
        │  IncidentContext JSON（冻结契约 v2-2.1）
        ▼
Python FastAPI  ai-diagnosis-service/  (127.0.0.1:8000)
        │  契约校验(extra=forbid + 版本闸门) → Prompt Builder
        ▼
DeepSeek（OpenAI 兼容，单次调用、无 retry）
        │  LLM 只输出语义字段：diagnosis_status / root_cause /
        │  evidence(path,note) / recommended_actions(action,rationale) / insufficient_reason
        ▼
JSON 解析 → evidence.path 回校验（observed 取 Context 真实值）→ DiagnosisResult
```

### 10.1 边界

- 只读：不连 Redis/MySQL、不执行任何运维动作、不 resolve Incident；
- 不修改 V2-1/V2-2 冻结契约；契约升级必须通过 `context_version` 显式升版（输入模型 `extra="forbid"`）；
- 无 RAG / LangChain / Agent / MCP / 向量库（留 V2-5）；
- 模型不可用一律降级为 `diagnosis_status=UNAVAILABLE` 且 HTTP 200，Java 调用方无需为 AI 可用性写异常分支。

### 10.2 防幻觉

`evidence[].path` 使用冻结语法（`.属性` + `[下标]`，如 `queue.dead_letter_entries_for_voucher[0].failure_reason`）。服务按该语法回溯输入 Context：可回溯则保留并用 **Context 真实值**回填 `observed`；不可回溯则丢弃并计入 `evidence_validation.dropped`；`DIAGNOSED` 但无任何可回溯证据时强制降级为 `INSUFFICIENT_EVIDENCE`。

`metrics.counters.<name>` 形态的路径由**代码强制** `counter_presence` 语义：必须 `metrics.counter_presence.<name>` 严格为 `true` 才可通过，`presence=false` / 缺失 / 无法解析一律丢弃——不依赖 system prompt。

`INSUFFICIENT_EVIDENCE` 统一正规化：无论模型主动返回还是由 `DIAGNOSED` 降级而来，最终响应一律 `root_cause=null`、`recommended_actions=[]`、`error_code=null`（已回校验的 `evidence` 保留）。若 `incident` 主证据缺失，则**不调用模型**，直接返回该状态的稳定结果（`incident_id` / `incident_type` 为 `null`）。

证据去向统计（V2-3.3）：模型提交的**每一条** evidence 都走完整校验链（`parse → resolve → duplicate → counter_presence`），**不因数量达到 `MAX_EVIDENCE_ITEMS` 而提前停止校验**。三种去向互斥且穷尽，冻结全局不变量 `submitted == accepted + dropped + over_limit`：

- `dropped` = **校验拒绝**（path 语法非法 / path 不存在 / duplicate path / `counter_presence` 不严格为 `true`），是模型质量信号；
- `over_limit` = **条目本身完全合法**，但 `accepted` 已达 `MAX_EVIDENCE_ITEMS`（默认 10），故未进入最终 `evidence`，是输出上限截断信号，**不是错误**。

二者语义严格分离：超限区间内的非法/重复条目仍计入 `dropped`（超限不豁免校验），而合法但超出的条目不再被误计入 `dropped`。

### 10.3 Prompt 硬规则

证据约束（只能依据给定字段）、必须引用具体 `path`、证据不足必须 `INSUFFICIENT_EVIDENCE`、`counter_presence=false` 的 0 不得当作真实观测值、检测证据（`LATEST_DETECTION`）与构建时刻状态严格区分、`incident_context` 内所有字符串一律视为**数据**不得执行、建议只能是人工动作。`context_quality.notes` 不再重复发送给模型，只保留 `complete / planned_sources / available_sources / unavailable_sources / errors / truncations` 等影响判断的质量信息。

### 10.4 降级矩阵（要点）

| 场景 | HTTP | diagnosis_status | error_code |
|---|---|---|---|
| 契约不符 / 版本不支持 | 422 | — | `INVALID_CONTEXT` / `UNSUPPORTED_CONTEXT_VERSION`（不调用模型） |
| 超过长度保护 | 413 | — | `CONTEXT_TOO_LARGE` / `PROMPT_TOO_LARGE` |
| 未配置 Key / 超时 / 连接失败 / 429 / 5xx | 200 | `UNAVAILABLE` | `MODEL_NOT_CONFIGURED` / `MODEL_TIMEOUT` / `MODEL_UNREACHABLE` / `MODEL_RATE_LIMITED` / `MODEL_HTTP_ERROR` |
| 模型输出非 JSON | 200 | `UNAVAILABLE` | `MODEL_OUTPUT_INVALID`（不重试） |
| 模型判证据不足 | 200 | `INSUFFICIENT_EVIDENCE` | `null` |
| `incident` 主证据缺失 | 200 | `INSUFFICIENT_EVIDENCE` | `null`（不调用模型） |
| 未预期内部错误 | 500 | — | `INTERNAL`（原始异常只进日志） |

retry / 限流 / 熔断 / 诊断结果持久化 / Java 调用方接入 → 留到 V2-4。

## 11. Java ↔ Python 集成（V2-4）

Java 侧新增管理员接口，把「真实故障证据」交给独立 Python 服务做结构化诊断：

```text
GET /admin/incidents/{id}/diagnosis      （X-Admin-Token，继承 /admin/incidents/** 规则）
        │
        ├─ IncidentContextBuilder.build(id)          ← 复用 V2-2，只读采集真实证据
        │     └─ Incident 不存在 → 404 INCIDENT_NOT_FOUND（不调用 Python）
        ▼
Sentinel 限流（资源 incident-diagnosis，默认 1 QPS）
        │  被限流 → 200 + UNAVAILABLE + AI_RATE_LIMITED（attempts=0，不调用 Python）
        ▼
AiDiagnosisClientImpl
        │  POST {ai-diagnosis.url}  {"incident_context": {...}}
        │  connect 2s / read 45s；有界重试（见 11.3）
        ▼
Python V2-3 DiagnosisService → DiagnosisResult
        │  200：类型化透传（含 V2-3 冻结语义校验）
        └─ 失败：Java 本地降级为 200 + UNAVAILABLE + AI_*
```

### 11.1 边界

- 零新增 Maven 依赖（`RestTemplate` + Spring Boot 注入的 `ObjectMapper`）；不引入 WebClient / Feign / Spring Retry / Resilience4j / 熔断器框架；
- 只读：不 resolve Incident、不改库存、不 ACK、不自动执行任何修复动作；
- **不改动 Python（V2-3）与 V2-1/V2-2 冻结逻辑**，也不复制修改 V2-2 `IncidentContext` 契约（请求体直接复用同一 DTO）；
- 诊断接口是独立只读端点，**不在秒杀主链路上**；AI 失败只影响该端点，且客户端**永不抛异常**；
- 不做 RAG / 向量库 / Runbook / 持久化 / Vue / Docker（V2-5 及以后）。

### 11.2 类型化契约与错误码归属

响应 DTO `com.hmdp.dto.diagnosis.AiDiagnosisResult` 是 Python `DiagnosisResult` 的**类型化镜像**（含 `evidence_validation.submitted/accepted/dropped/over_limit`），另有 4 个 Java 集成层遥测字段（仅 `error_origin`/`python_error_code`/`http_status` 在失败时出现）：

| 字段 | 来源 | 说明 |
|---|---|---|
| `diagnosis_status` / `context_version` / `incident_id` / `incident_type` / `root_cause` / `evidence[]` / `recommended_actions[]` / `insufficient_reason` / `error_code` / `evidence_validation` / `model` / `prompt_version` / `diagnosed_at` / `elapsed_ms` | Python | 原样透传；`observed` 用 `Object`（真实值类型不定） |
| `error_origin` | Java | `PYTHON`（错误码来自 Python）/ `JAVA_INTEGRATION` |
| `python_error_code` | Java | Java 降级时保留 Python 错误体的 `error_code`（如 `INVALID_CONTEXT`），不丢线索 |
| `http_status` | Java | Java 实际收到的状态码：收到响应时为真实值（成功透传即 200），**未收到响应时恒为 0**，不得为 null 或缺字段 |
| `attempts` | Java | 实际尝试次数；未发起调用（禁用/限流）为 0 |

**错误码命名约定**：`AI_*` 前缀**只由 Java 集成层生成**（`AI_SERVICE_DISABLED` / `AI_RATE_LIMITED` / `AI_SERVICE_CONNECT_TIMEOUT` / `AI_SERVICE_READ_TIMEOUT` / `AI_SERVICE_UNREACHABLE` / `AI_SERVICE_UNAVAILABLE` / `AI_SERVICE_HTTP_5XX` / `AI_SERVICE_HTTP_4XX` / `AI_SERVICE_REQUEST_REJECTED` / `AI_SERVICE_URL_INVALID` / `AI_RESPONSE_INVALID` / `AI_REQUEST_INVALID`）；其余取值均为 Python `ErrorCode` 原样返回。

### 11.2.1 响应校验（200 才校验，任一违反 → `AI_RESPONSE_INVALID`，不重试）

- 契约完整性：`context_version` / `diagnosis_status` / `evidence` / `recommended_actions` / `prompt_version` / `diagnosed_at` / `elapsed_ms` / `evidence_validation`（四字段齐全）必须存在；
- **请求相关性**：`context_version` == 请求 Context 的 `context_version`、`incident_id` == 请求的 `incident.id`、`incident_type` == 请求的 `incident.incident_type`（防串包 / 缓存 / 版本错配）；
- V2-3.3 不变量：`submitted == accepted + dropped + over_limit`、`evidence.size() == accepted`；
- 状态语义：`DIAGNOSED` 需 `root_cause` 非空且 `accepted > 0`；`INSUFFICIENT_EVIDENCE` 需 `root_cause`/`recommended_actions`/`error_code` 均为空；`UNAVAILABLE` 需 `error_code` 非空。

### 11.3 失败语义与重试边界（有界，不泛化成容错平台）

| 场景 | Java 行为 | error_code | 是否重试 |
|---|---|---|---|
| Python 200 `DIAGNOSED` / `INSUFFICIENT_EVIDENCE` | 类型化透传 | Python 原值（通常 `null`） | — |
| Python 200 `UNAVAILABLE` | 透传（服务可达，模型侧降级） | Python 原值（`MODEL_*`） | 否 |
| 200 但响应违反冻结语义 / 不可解析 / 空体 | 本地降级 | `AI_RESPONSE_INVALID` | 否 |
| 4xx（422 / 413） | 本地降级 | `AI_SERVICE_REQUEST_REJECTED`（+`python_error_code`） | 否 |
| 其它 4xx | 本地降级 | `AI_SERVICE_HTTP_4XX` | 否 |
| 500 | 本地降级 | `AI_SERVICE_HTTP_5XX` | 否 |
| **502 / 503 / 504** | 重试 1 次；仍失败则本地降级 | `AI_SERVICE_HTTP_5XX` | **是（≤1 次）** |
| connect timeout | 重试 1 次；仍失败则本地降级 | `AI_SERVICE_CONNECT_TIMEOUT` | **是（≤1 次）** |
| connection refused / unknown host | 重试 1 次；仍失败则本地降级 | `AI_SERVICE_UNREACHABLE` | **是（≤1 次）** |
| **read timeout** | 本地降级 | `AI_SERVICE_READ_TIMEOUT` | **否** |
| 其它 I/O 失败 | 本地降级 | `AI_SERVICE_UNAVAILABLE` | 否 |
| `ai-diagnosis.url` 非法（缺失 scheme / 端口越界等本地 URI 配置错误） | 本地降级，异常不逃出客户端 | `AI_SERVICE_URL_INVALID` | 否 |
| `ai-diagnosis.enabled=false` | 本地降级，不发起 HTTP | `AI_SERVICE_DISABLED` | 否（attempts=0） |
| Sentinel 限流 | 本地降级，不发起 HTTP | `AI_RATE_LIMITED` | 否（attempts=0） |

Java 本地降级结果的冻结语义：`diagnosis_status=UNAVAILABLE`、`root_cause=null`、`evidence=[]`、`recommended_actions=[]`、`evidence_validation` 全 0、`model`/`prompt_version`/`diagnosed_at` **一律为 null**（不伪造 Python/模型侧信息），`elapsed_ms` 为 Java 实测值。任何失败都**不得**返回 `DIAGNOSED`，HTTP 一律 200。

`HttpURLConnection` 不暴露超时阶段，connect / read 超时按异常类型与消息 best-effort 区分；无法判定时按 read timeout 处理（不重试）——宁可少一次重试，也不违反「read timeout 不重试」。

### 11.4 超时、重试与限流参数

| 参数 | 默认值 | 说明 |
|---|---|---|
| `ai-diagnosis.enabled` | `true` | false 时直接本地降级 |
| `ai-diagnosis.url` | `http://127.0.0.1:8000/api/v1/diagnosis` | Python V2-3 端点 |
| `ai-diagnosis.connect-timeout-ms` | `2000` | 独立 `@Bean("aiDiagnosisRestTemplate")`，不复用全局 RestTemplate |
| `ai-diagnosis.read-timeout-ms` | `45000` | 必须大于 Python 侧 `deepseek_timeout_seconds=40s` |
| `ai-diagnosis.max-attempts` | `2` | 1 次调用 + 最多 1 次额外重试；无退避；**运行时代码硬限制在 [1,2]**，配置为 3/99 也只尝试 2 次 |
| `ai-diagnosis.qps` | `1` | Sentinel 资源 `incident-diagnosis`；规则与既有规则**合并**加载 |

### 11.5 权限

`/admin/incidents/{id}/diagnosis` 命中 `AdminAuthInterceptor` 的 `/admin/incidents` 前缀规则：GET 也必须携带 `X-Admin-Token`，无/错令牌返回 403，拦截器与 MvcConfig **零改动**。

## 12. Runbook KB 与 RAG（V2-5）

在 IncidentContext（本次事故的事实）之外，Python 诊断服务还会注入**人工评审的通用运维知识**，帮助模型组织排查步骤；知识本身**不是**本次事故的事实。

```text
GET /admin/incidents/{id}/diagnosis
        │  IncidentContext（V2-2 冻结契约）
        ▼
Python V2-5 DiagnosisService
        ├─ RunbookRetriever ← runbooks/*.yaml（启动时一次性加载，构建后只读内存）
        │     incident_type 硬过滤 → signal_hit*2 + keyword_hit*1 → score 降序 / id 升序 → Top-2
        ▼
  build_prompt：<incident_context>（事实）+ <runbook_knowledge>（通用知识）
        ▼
  DeepSeek 单次调用 → evidence 回校验（仍只认 IncidentContext）→ DiagnosisResult
```

### 12.1 边界（冻结）

- 知识只来自 `ai-diagnosis-service/runbooks/*.yaml`（人工评审），**不引入** LangChain / LlamaIndex / 向量库 / Elasticsearch / embedding 服务；
- 检索是**纯本地确定性计算**：无第三方依赖（仅用已在环境中的 PyYAML 读文件）、无分词依赖、无第二次模型调用；
- `evidence[].path` **只能**来自 IncidentContext：`validate_evidence` 只在 Context 上解析 path，因此 Runbook 内容**结构上**不可能成为证据；
- Python 响应契约（`DiagnosisResult`）与 Java V2-4 DTO/校验**零改动**：检索过程只进 Python 日志与 `/healthz` 计数；
- 知识条目只有 `summary` / `checks` / `do_not` 进入 prompt（有长度上限），其余字段仅参与检索与审计。

### 12.2 检索语义

| 环节 | 规则 |
|---|---|
| 硬过滤 | `incident_types` 不含本次 `incident_type` → 排除 |
| 打分 | 命中 `match_signals` 每条 +2；命中 `keywords` 每个 +1（最多计 3 个，子串匹配、小写比较） |
| 排序 | `score` 降序 → `id` 升序（稳定、可复现） |
| Top-K | **2**（常量，不做配置项） |
| 阈值 | 无阈值：类型匹配即候选，是否注入由长度上限决定 |
| 长度上限 | `MAX_RUNBOOK_SECTION_CHARS`（默认 4000），超限**整条丢弃**低排名条目（不截断正文） |
| 无命中 | 注入显式空标记，明确告知"本次没有知识条目" |
| 信号闭集 | 信号名取自闭集词表（计数器/死信/消费者/快照/Redis 状态）；阈值严格复用项目既有定义：`pending > 1000`、心跳 `> 30000ms`、成功心跳 `> 60000ms` |

### 12.3 降级边界

| 场景 | 行为 |
|---|---|
| KB 目录缺失 / 不可读 / 无 YAML / 全部条目非法 | `rag_ready=false` + WARN，**服务照常启动**，诊断退化为 no-RAG |
| 单条知识非法（YAML 语法 / schema / 未知信号 / 重复 id） | 跳过该条并计入 `invalid_runbook_count`，其余条目可用 |
| `RAG_ENABLED=false` | 不读 KB、不检索 |
| 请求期检索异常 | 捕获 + WARN，本次诊断 no-RAG（**仍只有一次 DeepSeek 调用**） |

只有 Settings 非法或确定性程序初始化错误才 fail fast；KB 问题**永不**阻塞服务启动。

### 12.4 与 Java 的关系

Java 侧**零改动**：不感知 Runbook 的存在，仍按 V2-4.1 校验响应（相关性 + V2-3.3 不变量 + 状态语义）。
V2-5 将 `PROMPT_VERSION` 升为 `v2-5.1`（V2-6 起为 `v2-6.1`；Java 只要求该字段非空）。
检索结果（命中 id 与分数）只写入 Python 日志，供人工审计。

## 13. claim-level grounding（V2-6）

V2-3 的证据回校验保证"证据本身真实"；V2-6 补上"结论必须挂到真实证据上"这一层，解决
`root_cause` / `recommended_actions` 可能"比证据更强"的问题（例如把"当前两端一致且事件已 RESOLVED"
写成"已被某流程修复"）。

```text
模型输出（同一次调用）
  ├─ evidence[]                       ← 唯一证据池
  ├─ root_cause + root_cause_evidence_paths      ← claim citation（只引用上面的 path）
  └─ recommended_actions[] + evidence_paths      ← action citation
        ▼
  稳定重排序 evidence（root citation → action citation → 其余；不增删条目）
        ▼
  evidence_validator.validate_evidence()          ← 仍是 path / observed / counter_presence 唯一权威
        ▼
  以最终 accepted evidence.path 集合校验 citation
        ├─ root_cause 无有效 citation → INSUFFICIENT_EVIDENCE（正规化）
        └─ 单条 action 无有效 citation → 只丢弃该 action
```

### 13.1 冻结规则

- citation **不是第二套 evidence**：`root_cause_evidence_paths` / action `evidence_paths` 只能引用同一次输出里
  已经存在的 `evidence[].path`；不要求模型之外的任何解析，**不新增第二套 validator**；
- `services/evidence_validator.py` **零改动**，仍是 path 合法性 / `observed` 回填 / `counter_presence` 的唯一来源；
- 调用 `validate_evidence()` 之前只允许对 `parsed.evidence` 做**稳定重排序**（不新增、不删除、不去重）：
  root citation 对应条目优先，其次 action citation，最后是未被引用的条目（分桶内保持原相对顺序）；
- 因此 `submitted / accepted / dropped / over_limit` 四统计语义与 V2-3.3 **完全一致**
  （真实 `15/10/0/5` 场景仍为 `15/10/0/5`），只有 `accepted` 的**具体 path 集合**可能因优先级变化；
- `DIAGNOSED` 必须至少有 **1 条** root citation 最终 accepted；为空或全无效 → 降级 `INSUFFICIENT_EVIDENCE`；
  部分有效 → 保留 `DIAGNOSED`，只记录 invalid 计数；
- 单条 action 至少有 1 条 citation 最终 accepted 才保留，否则**只丢弃该条**（不降级整份诊断）；
- citations 仅内部使用：不进入 `DiagnosisResult`、不进入 Java、不进入 `evidence_validation` 统计、不落库；
- 日志只记录计数（`root_cited` / `root_accepted` / `invalid` / `dropped_actions` / `downgraded`），
  不打印 claim 正文、Context 或 Runbook 正文；
- 仍然只有**一次** DeepSeek 调用；无 verifier LLM、无 NLI、无新框架。

### 13.2 与 Java 的关系

Java **零改动**：`DiagnosisResult` 字段集合不变，V2-4.1 的相关性/非空/V2-3.3 不变量/状态语义校验全部照旧。
`PROMPT_VERSION` 升为 `v2-6.1`（Java 只校验非空）。

## 14. 数据库

数据库包含五张表：

```text
tb_user
tb_voucher
tb_seckill_voucher
tb_voucher_order
tb_incident
```

初始化脚本位于 `src/main/resources/db/hmdp.sql`，不包含用户手机号或课程样例数据。既有环境升级故障事件表执行 `src/main/resources/db/incident-migration.sql`（幂等）。

## 15. 关闭顺序

应用关闭时先停止 Pending 定时认领，再停止主消费者拉取，等待执行中的任务结束，最后更新消费者健康状态，减少消息处理中断窗口。
