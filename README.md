<p align="center">
  <h1 align="center">⚡ hm-dianping</h1>
  <p align="center">高并发秒杀系统 · Spring Boot 2.7 · Redis · MyBatis-Plus</p>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/JDK-11-orange?logo=openjdk" />
  <img src="https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen?logo=springboot" />
  <img src="https://img.shields.io/badge/MyBatis--Plus-3.4.3-blue" />
  <img src="https://img.shields.io/badge/Redis-Stream%20%2B%20Lua-red?logo=redis" />
  <img src="https://img.shields.io/badge/Monitor-Prometheus%20%2B%20Grafana-purple?logo=prometheus" />
</p>

---

## 📖 简介

聚焦**秒杀优惠券**核心链路，从库存预占、异步消费、缓存防护到 AI 诊断、全链路可观测，覆盖高并发系统设计的关键环节。

## 🧱 技术栈

| 分层 | 技术 |
|------|------|
| 框架 | Spring Boot 2.7.18 · JDK 11 |
| 持久层 | MySQL 5.x · MyBatis-Plus 3.4.3 |
| 缓存 | Redis (Lettuce) · Redisson 3.13.6 |
| 消息队列 | Redis Stream (消费者组 + 死信) |
| 限流 | Sentinel 1.8.6 |
| 监控 | Micrometer → Prometheus → Grafana |
| AI | DeepSeek API (deepseek-chat) |
| 工具 | Hutool 5.7.17 · Lombok |

## 📁 项目结构

```
src/main/java/com/hmdp/
├── cache/          CacheClient · CacheWarmupRunner · RedisData
├── config/         Mvc · MyBatis · Redisson · RestTemplate · SeckillProperties
├── constant/       Redis · Metrics · System · Regex
├── controller/     VoucherOrder · Voucher · Shop · ShopType · User · Metrics · Admin
├── dto/            Result · UserDTO · AiAnalyzeResult · ScrollResult
├── entity/         Voucher · SeckillVoucher · VoucherOrder · Shop · User · …
├── exception/      SeckillExceptions (Stock / Duplicate / OrderCreate / StreamConsume)
├── interceptor/    LoginInterceptor · RefreshTokenInterceptor · UserHolder
├── mapper/         MyBatis-Plus Mapper
├── monitor/        HealthIndicator · MetricsBinder · HealthMetricsExporter · Sentinel · DeepSeek
├── service/        接口 + impl（VoucherOrder / Shop / Metrics / AiAnalyze …）
└── utils/          RedisIdWorker · SimpleRedisLock · PasswordEncoder · RegexUtils
```

## ⚡ 秒杀核心链路

```
POST /voucher-order/seckill/{id}
        │
        ▼
┌─────────────────────────────────────┐
│  seckill.lua (Redis 原子执行)        │
│  ├─ GET stock  → 库存检查            │
│  ├─ SISMEMBER  → 一人一单去重         │
│  ├─ INCRBY -1  → 扣库存              │
│  ├─ SADD       → 标记已下单           │
│  └─ XADD       → 投递 Stream 消息     │
└─────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────┐
│  Redis Stream (stream.orders)       │
│  消费组 g1 · 多线程负载均衡           │
│  批量拉取 → 逐条处理 → ACK           │
└─────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────┐
│  handleVoucherOrder                 │
│  └─ createVoucherOrder (@Transactional)
│      ├─ UPDATE stock - 1 (乐观锁)    │
│      └─ INSERT voucher_order        │
└─────────────────────────────────────┘
```

### 🔑 关键设计

| 设计点 | 说明 |
|--------|------|
| **Lua 原子脚本** | 5 步合 1，单次 Redis 往返，无竞态 |
| **Stream 异步削峰** | 生产消费解耦，批量拉取减少网络开销 |
| **Pending 独立处理器** | 定时 XCLAIM 认领超时消息，带 5s 宽限期，重试超限 → 死信 + 库存补偿 |
| **乐观锁兜底** | `WHERE stock > 0` 防超卖，最终一致性 |
| **死信 + 库存回退** | 死信自动补偿 Redis 库存，防止偏差累积 |
| **定时对账** | 脏券驱动，两阶段告警，只告警不自动修 |
| **优雅关闭** | `@PreDestroy` 停 Pending → 停消费 → 等 Pending 清完 |
| **配置集中化** | `SeckillProperties` 管理全部秒杀参数 |

## 🆔 全局 ID 生成

`RedisIdWorker`：高 32 位秒级时间戳 + 低 32 位 Redis 自增序列，按天分区，全局唯一趋势递增。

## 🛡 缓存策略

| 问题 | 方案 |
|------|------|
| 穿透 | 空值缓存 TTL 2min |
| 击穿 | 逻辑过期 + Redisson 看门狗异步重建 |
| 冷启动 | `CacheWarmupRunner` 全量预热 |
| 新增 | 写 DB 同步写 Redis |
| 更新 | 先更新 DB → 删缓存 → 下次查重建 |

## 🤖 AI 智能诊断

对接 DeepSeek，四层语义模型 → 结构化 Prompt → 根因分析：

```
系统能力层 ──→ 业务结果层 ──→ 链路转化层 ──→ AI 语义输入层
 (capacity)     (business)     (funnel)       (diagnosis)
```

- **历史基线对比** — 每轮诊断后存入 Redis，下轮对比识别趋势恶化
- **主状态判定** — NORMAL / SATURATED / DEGRADED / INFRA_FAIL / CRITICAL
- **结构化输出** — 关键症状 + 因果链 + 可执行建议
- **防御性设计** — 证据约束，禁止凭空推测，失败 fallback

## 📊 可观测性

```
Redis 计数器 ──→ Micrometer Gauge ──→ Prometheus ──→ Grafana
                                                    ──→ Alertmanager
```

- **10 个原始计数器 + 6 个计算比率**，覆盖请求/预占/成交/消费/对账全链路
- **双心跳健康检查** (alive + success)，区分"活着"和"正常工作"
- **5 条 Prometheus 告警规则** (消费者挂掉/心跳超时/停滞/成功率低/基础设施故障)
- **Grafana Dashboard** `grafana-dashboard-seckill.json` 开箱即用
- **运维接口** `/admin/*` 紧急启停、死信重放、手动对账、指标重置

## 🚀 快速开始

### 环境

- JDK 11+ · MySQL 5.x · Redis · Maven 3.6+

### 1. 初始化数据库

```sql
CREATE DATABASE hmdp DEFAULT CHARACTER SET utf8mb4;
-- 导入 db/ 目录下的 SQL 脚本
```

### 2. 配置

```bash
cp src/main/resources/application-template.yaml src/main/resources/application.yaml
```

编辑 `application.yaml`，填入数据库和 Redis 连接信息。

### 3. 启动

```bash
export DEEPSEEK_API_KEY=sk-xxxxx        # AI 诊断需要（可选）
mvn clean package -DskipTests
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

服务启动在 **8081** 端口。

## 🔌 接口速览

### 秒杀

| 方法 | 接口 | 说明 |
|------|------|------|
| POST | `/voucher-order/seckill/{id}` | 秒杀优惠券 |
| GET | `/voucher-order/seckill/{id}/status` | 查询订单状态 |

### 店铺 & 优惠券

| 方法 | 接口 | 说明 |
|------|------|------|
| GET | `/shop/{id}` | 店铺详情 |
| POST | `/shop` | 新增店铺 |
| PUT | `/shop` | 更新店铺 |
| GET | `/shop-type/list` | 店铺类型 |
| GET | `/shop/of/type` | 按类型分页 |
| GET | `/shop/of/name` | 按名称搜索 |
| POST | `/voucher` | 新增普通券 |
| POST | `/voucher/seckill` | 新增秒杀券 |
| GET | `/voucher/list/{shopId}` | 店铺优惠券列表 |

### 用户

| 方法 | 接口 | 说明 |
|------|------|------|
| POST | `/user/code` | 发送验证码 |
| POST | `/user/login` | 登录 |
| GET | `/user/me` | 当前用户信息 |

### 运维

| 方法 | 接口 | 说明 |
|------|------|------|
| GET | `/admin/seckill/{id}/stats` | 秒杀实时状态 |
| POST | `/admin/seckill/{id}/stop` | 紧急停止 |
| POST | `/admin/seckill/{id}/resume` | 恢复秒杀 |
| POST | `/admin/dead-letter/replay` | 重放死信 |
| POST | `/admin/reconcile/trigger` | 手动对账 |
| GET | `/admin/health/queue` | 队列健康 |
| POST | `/admin/metrics/reset` | 重置指标 |

### 监控

| 方法 | 接口 | 说明 |
|------|------|------|
| GET | `/actuator/health` | 健康检查 |
| GET | `/actuator/prometheus` | Prometheus 指标 |
| GET | `/metrics/seckill` | 秒杀指标 |
| GET | `/metrics/ai/analyze` | AI 诊断 |

## 📈 压测数据

> JMeter 2000 并发 × 400 库存

| 指标 | 数值 |
|------|------|
| 超卖 | 0 |
| TPS | **1345.9** |
| 平均响应 | 606ms |
| 瓶颈 | Redis 单线程排队 |
