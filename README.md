<h1 align="center">Seckill Observability & AI Diagnosis</h1>

<p align="center">高并发秒杀 · 可观测性 · AI 辅助故障诊断</p>

<p align="center">
  <img src="https://img.shields.io/badge/JDK-11-orange?logo=openjdk" alt="JDK 11" />
  <img src="https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen?logo=springboot" alt="Spring Boot 2.7.18" />
  <img src="https://img.shields.io/badge/Redis-Stream%20%2B%20Lua-red?logo=redis" alt="Redis Stream and Lua" />
  <img src="https://img.shields.io/badge/Monitor-Prometheus%20%2B%20Grafana-blue?logo=prometheus" alt="Prometheus and Grafana" />
</p>

面向高并发秒杀场景的可验证工程实现：Redis Lua 原子预占资格，Redis Stream 异步削峰，Pending/XCLAIM 限次重试与安全死信隔离保障可靠落库，并以库存对账、Prometheus/Grafana 指标，以及 Incident → IncidentContext → AI 诊断，形成可观测、可追溯的故障诊断链路。

## 项目来源与个人改造边界

本项目基于黑马点评教学项目进行二次重构。个人改造聚焦秒杀主链路：删除店铺、博客等非核心模块，补充 Redis Stream 异步下单的 Pending/XCLAIM 恢复、安全死信隔离、库存对账、Prometheus/Grafana 监控，以及基于 Incident 与结构化 IncidentContext 的 AI 故障诊断（独立 Python FastAPI 服务 + Runbook 检索）。原有 `com.hmdp` 包名与 `hmdp` 数据库名作为兼容性边界保留，避免与业务能力无关的大范围迁移。

## 核心链路

```text
HTTP 请求
   |
   v
Lua 原子预占
   |-- 活动状态 / begin / end
   |-- 库存校验
   |-- 一人一单
   +-- XADD stream.orders
   |
   v
Redis Stream 消费者组
   |
   +--> 正常消费 --> MySQL stock > 0 条件扣减 --> unique(user_id, voucher_id) --> XACK
   |
   +--> 未 ACK --> PEL
                  |
                  v
             Pending Handler（XPENDING / XCLAIM）
                  |
                  v
             限次重试 --> 超限 --> dead-letter.lua
                                    XACK + XADD DLQ + DEL retry key
                                    不回补库存 / 不释放资格
                                    |
                                    v
                          DEAD_LETTER Incident（OPEN）
                                    |
                                    v
                                  人工核查


一致性：订单变更 --> dirty set --> 定时 reconcile（Redis vs MySQL）
                                  |-- 首次不一致：只写 marker
                                  +-- 仍不一致：上报 INVENTORY_MISMATCH Incident

        另有低频有界 DB 分页 fallback --> 只重新标 dirty（不修改任何库存）


诊断：Incident --> IncidentContext v3.0 --> Java AiDiagnosisClient
                                          --> Python FastAPI
                                              |-- Runbook 确定性 Top-2
                                              |-- DeepSeek 结构化输出
                                              +-- evidence.path 服务端回溯 + observed 覆盖
```

## 核心设计

| 设计 | 工程实现 |
|---|---|
| 原子资格预占 | Lua 将活动时间闸门、库存检查、一人一单、扣库存、资格记录和 Stream 投递合并为一次 Redis 原子操作 |
| 可靠异步落库 | Redis Stream 消费者组批量拉取；Pending 处理器通过 `XPENDING` + `XCLAIM` 认领超时消息并限次重试 |
| 安全死信隔离 | 重试超限后原子执行 `XACK` + `XADD` 死信队列 + 删除重试计数；**不自动回补 Redis 库存、不释放下单资格**，避免 DB 晚提交造成重复释放，交人工核查 |
| 数据库一致性兜底 | `UPDATE ... WHERE stock > 0` 防超卖；`unique(user_id, voucher_id)` 防重复订单；`DuplicateKeyException` 后仍须确认**该 orderId** 已落库才按幂等处理 |
| 两阶段库存对账 | dirty set 驱动；首次不一致只写 marker，**连续两轮**确认偏差后才上报 Incident，降低异步落库窗口造成的瞬时误报 |
| 有界兜底扫描 | 低频有界 DB 分页扫描，仅把发现的券重新标记为 dirty，**不修改任何库存**；复用同一个 `reconcile()` |
| 统一故障事件 | 对账偏差、死信、消费者不健康统一抽象为 Incident，按 `incidentType + businessKey` 聚合；严重级别只升不降 |
| 故障上下文构建 | 按 IncidentType 计划采集范围，收集真实证据（Incident / Redis / Database / Queue / Consumer Health），输出 `IncidentContext` v3.0 契约，只收证据不做推断 |
| 结构化 AI 诊断 | `ai-diagnosis-service/`（Python 3.12 + FastAPI）只消费 `IncidentContext`；模型只输出语义字段，`evidence.path` 由服务端回溯 Context 并用真实值覆盖 `observed` |
| 可观测 | 业务计数、链路转化率、消费者心跳、Pending、死信和对账偏差统一采集到 Prometheus / Grafana |

## 当前版本压测结果

> 在**当前 Resume-Lite 代码基线 `0ac054c`** 上重新完成三轮 2000 用户、400 库存、10 秒 Ramp-up 测试；三轮均完成 400/400 资格预占异步落库，其余请求由 Redis 库存层拦截。三轮中位数：Avg **58.10 ms**、P95 **230.6 ms**、P99 **383.9 ms**、TPS **236.41 req/s**，服务端错误 0、Pending 0、死信 0、库存一致。
>
> Run 3 的 JMeter 汇总含 1 条**客户端** `BindException: Address already in use`（本机临时端口耗尽，请求未到达服务端），故该轮服务端只收到 1999 个请求。已如实记录、未重跑美化；数据库侧三张券均为 400 单、库存 0、无重复订单。

单机结果仅用于当前硬件与测试模型下的回归比较，不作为生产环境性能承诺。逐轮延迟、吞吐、业务一致性与 Run 3 异常的详细说明见 [benchmark/RESULTS.md](benchmark/RESULTS.md)。

## 可观测性与 AI 辅助诊断

- Micrometer 将 Redis 业务计数器、消费者健康状态和链路比率暴露给 Prometheus，Grafana 仪表板展示请求、预占、落库、失败、Pending、死信和对账偏差。
- 故障事件层把对账偏差、死信、消费者不健康统一落库为 Incident，支持按状态/类型/券查询；持续异常只聚合更新（`occurrence_count`）。`INVENTORY_MISMATCH` 与 `CONSUMER_UNHEALTHY` 在恢复后自动置为 `RESOLVED`；**`DEAD_LETTER` 当前保持 `OPEN` 等待人工核查**，系统不提供自动重放 / 补偿 / 恢复闭环。
- 内置 `dashboard.html` 是**秒杀运行监控页**：展示运行指标、Pending 与死信数量，并提供重置指标与手动对账按钮。它不再调用任何 AI 接口。
- AI 诊断入口是 `GET /admin/incidents/{id}/diagnosis`：复用同一个 Context Builder 采集真实证据，再调用独立 Python 服务（FastAPI + 确定性 Runbook Top-2 检索 + DeepSeek 结构化输出）返回根因 / 可回溯证据 / 人工建议。
- AI **只消费 `IncidentContext`**，不消费 Prometheus 指标投影。模型侧不可用时降级为 `diagnosis_status=UNAVAILABLE`；Java 集成层失败时同样本地降级为 `UNAVAILABLE` + `error_code`，绝不伪造成 `DIAGNOSED`，也不影响秒杀交易链路。
- Sentinel 只保护两个资源：`metrics`（指标接口）与 `incident-diagnosis`（诊断接口）；秒杀入口的库存竞争仍由 Redis Lua 处理。

## 技术栈

- 核心：Spring Boot 2.7.18、JDK 11、MyBatis-Plus、MySQL、Redis、Lua、Redis Stream
- 可观测性：Micrometer、Actuator、Prometheus、Grafana
- 辅助能力：DeepSeek API、Sentinel、FastAPI（独立 AI 诊断服务）
- 测试：JUnit 5、pytest、Lua contract 测试、JMeter

## 快速开始

### 1. 环境要求

- JDK 11+
- Maven 3.6+
- MySQL 5.7+
- Redis 6+
- Python 3.12（仅 AI 诊断服务需要）

### 2. 初始化数据库与配置

```bash
mysql -u root -p < src/main/resources/db/hmdp.sql
cp src/main/resources/application-template.yaml src/main/resources/application.yaml
```

至少配置数据库密码和管理员令牌。Redis 密码与 DeepSeek Key 可为空。

```bash
export MYSQL_PASSWORD=replace-me
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export REDIS_PASSWORD=
export ADMIN_TOKEN=replace-with-a-strong-random-token
export DEEPSEEK_API_KEY=sk-xxxxx
```

Windows PowerShell 使用 `$env:变量名="值"` 设置环境变量。

### 3. 构建与启动

```bash
mvn clean package
java -jar target/seckill-observability-ai-0.0.1-SNAPSHOT.jar
```

应用端口为 `8081`，运行监控页为 `http://127.0.0.1:8081/dashboard.html`。

### 4. 启动 AI 诊断服务（可选）

未启动时 `/admin/incidents/{id}/diagnosis` 会本地降级为 `UNAVAILABLE`，不影响其它功能。启动方式见 [ai-diagnosis-service/README.md](ai-diagnosis-service/README.md)。

## 回归测试与压测复现

应用和 Redis 启动后执行回归脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/regression.ps1 `
  -AdminToken $env:ADMIN_TOKEN
```

脚本验证 7 步：健康检查、验证码登录、创建秒杀券、Lua 预占与重复请求拦截、Stream 异步落库、死信隔离安全语义、库存对账与运行指标采集。脚本不调用外部 AI 服务。

JMeter 默认模拟 2000 用户（对应 2000 个线程）竞争 400 份库存，线程在 10 秒 Ramp-up 内逐步启动。完整准备、执行命令与参数见 [benchmark/README.md](benchmark/README.md)。

## 主要接口

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/voucher-order/seckill/{id}` | Lua 资格预占 |
| GET | `/voucher-order/seckill/{id}/status` | 查询异步订单状态 |
| GET | `/metrics/seckill` | 获取秒杀运行指标 |
| GET | `/admin/incidents` | 查询故障事件列表（支持 status/type/voucherId/limit） |
| GET | `/admin/incidents/{incidentId}` | 查询单个故障事件详情 |
| GET | `/admin/incidents/{incidentId}/context` | 构建该故障的 `IncidentContext` v3.0 |
| GET | `/admin/incidents/{incidentId}/diagnosis` | 基于该 Context 调用独立 FastAPI 诊断服务，失败降级为 `UNAVAILABLE` |
| GET | `/admin/seckill/{id}/stats` | 查看库存与队列状态 |
| POST | `/admin/reconcile/trigger` | 手动触发库存对账 |

故障事件只由系统内部检测逻辑产生，不提供创建接口；`DEAD_LETTER` 事件也没有人工关闭接口，保持 `OPEN` 等待人工核查。`/admin/incidents/**` 的只读查询同样要求 `X-Admin-Token`——故障事件包含业务键、关联券 id 与库存快照，属于敏感运维数据；其余 `/admin/**` 只读接口保持原有约定（GET 免令牌）。

`/admin/incidents/{id}/context` 实时构建、不落库，只收集真实读到的证据，**不做任何根因推断**；按 IncidentType 严格计划采集范围，单个数据源失败只降级该段并记入 `unavailable_sources`，HTTP 仍返回 200；Incident 不存在返回 404。输出契约见 [ARCHITECTURE.md](ARCHITECTURE.md) §8。

`/admin/incidents/{id}/diagnosis` 复用同一个 Context Builder，再调用独立 Python 诊断服务返回**结构化诊断**（根因 / 可回溯证据 / 人工建议）。AI 侧不可用、超时或响应非法时，Java 侧**有界失败**并返回 `diagnosis_status=UNAVAILABLE` + `error_code`：`AI_*` 前缀由 Java 集成层生成（如 `AI_SERVICE_UNREACHABLE` / `AI_SERVICE_READ_TIMEOUT` / `AI_RATE_LIMITED`），其余为 Python 原样返回（如 `MODEL_NOT_CONFIGURED`）；`error_origin` 明确区分两者。**不会伪造成 DIAGNOSED**，也不影响秒杀交易链路。契约、响应校验、超时 / 重试 / 限流策略见 [ARCHITECTURE.md](ARCHITECTURE.md) §10.2～§10.4。

用户接口通过 `authorization: <token>` 传递身份；运维写接口与故障事件查询通过 `X-Admin-Token: <ADMIN_TOKEN>` 鉴权。

## 目录与文档

- [ARCHITECTURE.md](ARCHITECTURE.md)：秒杀主链、失败恢复、对账与 AI 诊断的当前架构
- [ai-diagnosis-service/README.md](ai-diagnosis-service/README.md)：AI 诊断服务（接口、契约、降级矩阵、运行方式）
- [benchmark/README.md](benchmark/README.md)：JMeter 压测复现步骤
- [benchmark/RESULTS.md](benchmark/RESULTS.md)：压测结果记录（含剪枝前版本的历史数据）
- `src/main/resources/grafana-dashboard-seckill.json`：Grafana 仪表板
- `src/main/resources/static/dashboard.html`：秒杀运行监控页面
- `docs/history/` 与 [docs/V2-7-HARDENING.md](docs/V2-7-HARDENING.md)：开发过程与历史验证记录（非当前架构描述）

## 当前可靠性边界

- **活动闸门**：活动状态与库存分离，Lua 校验活动元数据及起止时间（边界含 begin/end）。已有券必须通过管理员 resume 补齐 `active/begin/end` 元数据，缺失时 fail closed；resume 不覆盖 Redis stock，缺失库存需要人工核查。
- **安全死信隔离**：重试超限后消息只做隔离，永不自动回补库存或释放资格。`DEAD_LETTER` 事件保持 `OPEN`，当前不实现自动重放、自动补偿或自动恢复。
- **对账**：dirty set 为主路径，另有低频有界 DB 分页 fallback，仅重新标脏、不修改库存。Redis stock key 缺失一律视为真实异常证据（`stock.present=false` / `value=null`），**绝不当作 0**。
- **消费者健康**：仅主消费循环刷新 main heartbeat；停滞告警要求 health UP 且 `pending > 0` 且成功心跳超时。
- **时区**：DB `LocalDateTime` 按 JVM 默认时区转 epoch millis，各实例需统一时区（例如 `-Duser.timezone=Asia/Shanghai`）。
- **未实现**：诊断持久化、操作历史、审计表、NLI / verifier 模型、第二次模型调用、分布式事务或新的分布式框架。

具体历史验证过程与剩余风险见 [V2-7 验证记录](docs/V2-7-HARDENING.md)。
