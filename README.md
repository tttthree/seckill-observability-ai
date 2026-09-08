<h1 align="center">Seckill Observability & AI Diagnosis</h1>

<p align="center">高并发秒杀与智能运维平台</p>

<p align="center">
  <img src="https://img.shields.io/badge/JDK-11-orange?logo=openjdk" alt="JDK 11" />
  <img src="https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen?logo=springboot" alt="Spring Boot 2.7.18" />
  <img src="https://img.shields.io/badge/Redis-Stream%20%2B%20Lua-red?logo=redis" alt="Redis Stream and Lua" />
  <img src="https://img.shields.io/badge/Monitor-Prometheus%20%2B%20Grafana-blue?logo=prometheus" alt="Prometheus and Grafana" />
</p>

面向高并发秒杀场景的可验证工程实现：Redis Lua 原子预占资格，Redis Stream 异步削峰，Pending/XCLAIM 恢复与死信补偿保障可靠落库，并以库存对账、Prometheus/Grafana 指标和 AI 辅助诊断形成闭环。

## 项目来源与个人改造边界

本项目基于黑马点评教学项目进行二次重构。个人改造聚焦秒杀主链路：删除店铺、博客等非核心模块，补充 Redis Stream 异步下单的 Pending 恢复、死信补偿与重放、库存对账、Prometheus/Grafana 监控，以及基于运行指标的 AI 故障诊断。原有 `com.hmdp` 包名与 `hmdp` 数据库名作为兼容性边界保留，避免与业务能力无关的大范围迁移。

## 核心链路

```text
HTTP 请求
   |
   v
Lua 原子预占（库存校验 + 一人一单 + XADD）
   |
   v
Redis Stream 消费者组
   |
   +--> 正常消费 --> MySQL 条件扣减与订单落库 --> XACK
   |
   +--> Pending/XCLAIM 重试 --> 死信补偿/重放
                                      |
                                      v
库存对账 --> Prometheus/Grafana --> AI 辅助诊断
```

## 核心设计

| 设计 | 工程实现 |
|---|---|
| 原子资格预占 | Lua 将库存检查、一人一单、扣库存、资格记录和 Stream 投递合并为一次 Redis 原子操作 |
| 可靠异步落库 | Redis Stream 消费者组批量拉取；Pending 处理器通过 `XCLAIM` 认领超时消息并限次重试 |
| 原子死信补偿与重放 | Lua 同时完成 ACK、Redis 库存/资格回补和死信投递；重放时重新校验并预占资格 |
| 数据库一致性兜底 | `UPDATE ... WHERE stock > 0` 防超卖，用户与优惠券联合唯一索引防重复订单 |
| 两阶段库存对账 | 脏券驱动，连续两次确认偏差后告警，降低异步落库窗口造成的瞬时误报 |
| 可观测与辅助诊断 | 业务计数、链路转化率、消费者心跳、Pending、死信和对账偏差统一采集，并输入 AI 生成结构化诊断建议 |

## 当前版本压测结果

> 当前版本在本机完成三轮 2000 用户、400 库存、10 秒 Ramp-up 测试；三轮均完成 400/400 资格预占异步落库，其余 1600 请求由 Redis 库存层拦截，Redis→MySQL 转化率 100%，Pending、死信及基础设施异常均为 0。三轮吞吐中位数约 247 req/s，平均响应时间中位数约 75 ms。

单机结果仅用于当前硬件与测试模型下的回归比较，不作为生产环境性能承诺。逐轮延迟、吞吐和业务一致性数据见 [benchmark/RESULTS.md](benchmark/RESULTS.md)。

## 可观测性与 AI 辅助诊断

- Micrometer 将 Redis 业务计数器、消费者健康状态和链路比率暴露给 Prometheus。
- Grafana 仪表板展示请求、预占、落库、失败、Pending、死信和对账偏差。
- 内置运维页 `dashboard.html` 汇总实时指标，并可调用 DeepSeek 输出主状态、关键症状、因果链和处置建议。
- AI 仅消费结构化运行指标并提供辅助诊断；未配置 API Key 时返回本地 `UNKNOWN`，不发送指标。
- Sentinel 仅保护指标与 AI 诊断接口，秒杀入口的库存竞争仍由 Redis Lua 处理。

## 技术栈

- 核心：Spring Boot 2.7.18、JDK 11、MyBatis-Plus、MySQL、Redis、Lua、Redis Stream
- 可观测性：Micrometer、Actuator、Prometheus、Grafana
- 辅助能力：DeepSeek API、Sentinel
- 测试：JUnit 5、JMeter

## 快速开始

### 1. 环境要求

- JDK 11+
- Maven 3.6+
- MySQL 5.7+
- Redis 6+

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

应用端口为 `8081`，运维诊断页面为 `http://127.0.0.1:8081/dashboard.html`。

## 回归测试与压测复现

应用和 Redis 启动后执行回归脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/regression.ps1 `
  -AdminToken $env:ADMIN_TOKEN
```

脚本验证验证码登录、秒杀券创建、Lua 预占、重复请求拦截、Stream 异步落库、死信补偿与重放、库存对账、指标采集和 AI 诊断入口。配置有效的 `DEEPSEEK_API_KEY` 后可增加 `-RequireAiDiagnosis`。

JMeter 默认模拟 2000 用户（对应 2000 个线程）竞争 400 份库存，线程在 10 秒 Ramp-up 内逐步启动。完整准备、执行命令与参数见 [benchmark/README.md](benchmark/README.md)。

## 主要接口

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/voucher-order/seckill/{id}` | Lua 资格预占 |
| GET | `/voucher-order/seckill/{id}/status` | 查询异步订单状态 |
| GET | `/metrics/seckill` | 获取结构化秒杀指标 |
| GET | `/metrics/ai/analyze` | 触发 AI 辅助诊断 |
| GET | `/admin/seckill/{id}/stats` | 查看库存与队列状态 |
| POST | `/admin/dead-letter/replay` | 原子重放死信 |
| POST | `/admin/reconcile/trigger` | 手动触发库存对账 |

用户接口通过 `authorization: <token>` 传递身份；运维写接口通过 `X-Admin-Token: <ADMIN_TOKEN>` 鉴权。

## 目录与文档

- [ARCHITECTURE.md](ARCHITECTURE.md)：秒杀、恢复、补偿、对账和指标模型设计
- [benchmark/README.md](benchmark/README.md)：JMeter 压测复现步骤
- [benchmark/RESULTS.md](benchmark/RESULTS.md)：当前版本三轮原始压测结果
- `src/main/resources/grafana-dashboard-seckill.json`：Grafana 仪表板
- `src/main/resources/static/dashboard.html`：运维与 AI 辅助诊断页面
