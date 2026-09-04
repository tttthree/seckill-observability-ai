<h1 align="center">hm-dianping seckill</h1>

<p align="center">高并发秒杀、可靠异步落库、全链路监控与 AI 运维诊断</p>

<p align="center">
  <img src="https://img.shields.io/badge/JDK-11-orange?logo=openjdk" alt="JDK 11" />
  <img src="https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen?logo=springboot" alt="Spring Boot 2.7.18" />
  <img src="https://img.shields.io/badge/Redis-Stream%20%2B%20Lua-red?logo=redis" alt="Redis Stream and Lua" />
  <img src="https://img.shields.io/badge/Monitor-Prometheus%20%2B%20Grafana-blue?logo=prometheus" alt="Prometheus and Grafana" />
</p>

## 项目定位

本项目基于黑马点评教学项目进行二次重构。个人改造聚焦秒杀主链路：删除店铺、博客等非核心模块，补充 Redis Stream 异步下单的 Pending 恢复、死信补偿与重放、库存对账、Prometheus/Grafana 监控，以及基于运行指标的 AI 故障诊断。

项目只保留一条可验证的核心链路：用户登录后请求秒杀，Lua 在 Redis 中原子完成资格与库存预占，Redis Stream 异步削峰并写入 MySQL；Pending 重试、死信与补偿、库存对账负责失败恢复，业务指标经 Micrometer 暴露给 Prometheus/Grafana，并作为 DeepSeek 的结构化诊断输入。

将秒杀链路中的请求量、预占成功率、数据库提交率、库存失败率、消费异常及对账异常等指标输入诊断模块，区分库存耗尽、消费阻塞、数据库异常等系统状态，并输出关键症状、因果链和处置建议。

## 核心链路

```text
POST /voucher-order/seckill/{id}
                 |
                 v
        seckill.lua 原子预占
   库存校验 + 一人一单 + 扣库存
       + 资格记录 + XADD 投递
                 |
                 v
       Redis Stream 消费者组
        新消息消费 / Pending 认领
                 |
                 v
       MySQL 条件扣减并创建订单
                 |
        +--------+---------+
        |                  |
      成功                超限失败
       XACK        原子 XACK + 库存/资格补偿
                         + 死信投递
                              |
                         管理员重放
```

## 关键设计

| 设计点 | 实现 |
|---|---|
| 原子资格预占 | Lua 将库存检查、一人一单、扣库存、资格记录和 Stream 投递合并为一次 Redis 操作 |
| 异步削峰 | Redis Stream 消费者组批量拉取，HTTP 请求不等待数据库写入 |
| 数据库兜底 | `UPDATE ... WHERE stock > 0` 防止超卖，`uk_user_voucher` 防止重复落单 |
| Pending 恢复 | 独立处理器使用 `XCLAIM` 认领超时消息并限制重试次数 |
| 原子死信补偿 | `dead-letter.lua` 同时完成 ACK、Redis 库存/资格回补和死信投递 |
| 原子死信重放 | `replay-dead-letter.lua` 重新预占库存与资格，并把消息投回主 Stream |
| 库存对账 | 脏券驱动，对 Redis 与数据库库存执行两阶段确认并告警，避免瞬时差异误报 |
| 管理员隔离 | 秒杀券配置及运维写接口必须携带独立的 `X-Admin-Token` |
| 可观测性 | 10 个业务计数器、消费者双心跳、Pending 和死信堆积统一暴露给 Prometheus |
| AI 诊断 | 指标按运行时、容量、业务结果、链路漏斗和诊断特征分层后提交给 DeepSeek |

## 技术栈

- Spring Boot 2.7.18、JDK 11、MyBatis-Plus
- MySQL、Redis、Redis Stream、Lua
- Micrometer、Actuator、Prometheus、Grafana
- Sentinel、DeepSeek API
- JUnit 5、JMeter

## 项目结构

```text
src/main/java/com/hmdp/
├── config/          MVC、管理员鉴权、秒杀参数、异常处理、HTTP 客户端
├── constant/        Redis Key、业务指标和正则常量
├── controller/      登录、秒杀券、秒杀订单、指标、运维
├── dto/             登录、用户身份、统一响应、AI 诊断结果
├── entity/          User、Voucher、SeckillVoucher、VoucherOrder
├── exception/       秒杀链路异常
├── interceptor/     登录身份恢复、登录校验、管理员令牌校验
├── mapper/          四张核心表的 MyBatis-Plus Mapper
├── monitor/         消费者健康、指标桥接、Sentinel、DeepSeek 配置
├── service/         秒杀、指标和 AI 诊断服务
└── utils/           Redis 全局 ID、手机号校验

src/main/resources/
├── db/hmdp.sql                    四张核心表
├── seckill.lua                    资格与库存原子预占
├── dead-letter.lua                原子死信补偿
├── replay-dead-letter.lua         原子死信重放
├── grafana-dashboard-seckill.json Grafana Dashboard
└── static/dashboard.html          运维诊断页面
```

更详细的设计见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 快速开始

### 1. 环境

- JDK 11+
- Maven 3.6+
- MySQL 5.7+
- Redis 6+

### 2. 初始化数据库

```bash
mysql -u root -p < src/main/resources/db/hmdp.sql
```

### 3. 创建配置

```bash
cp src/main/resources/application-template.yaml src/main/resources/application.yaml
```

至少设置数据库密码；Redis 密码和 DeepSeek Key 可为空。DeepSeek Key 或地址为空时，诊断接口会直接返回本地 `UNKNOWN` 降级结果，不会发送运行指标。管理员令牌为空时，所有受保护的写接口都会拒绝访问。

```bash
export MYSQL_PASSWORD=replace-me
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export REDIS_PASSWORD=
export ADMIN_TOKEN=replace-with-a-strong-random-token
export DEEPSEEK_API_KEY=sk-xxxxx
```

Windows PowerShell 使用 `$env:变量名="值"` 设置环境变量。

### 4. 启动

```bash
mvn clean package
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

应用端口为 `8081`，诊断页面为 `http://127.0.0.1:8081/dashboard.html`。

### 5. 创建秒杀券

```bash
curl -X POST http://127.0.0.1:8081/voucher/seckill \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: $ADMIN_TOKEN" \
  -d '{
    "title": "限量测试券",
    "stock": 400,
    "beginTime": "2026-09-02T00:00:00",
    "endTime": "2026-12-31T23:59:59"
  }'
```

## 接口

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| POST | `/user/code` | 无 | 生成验证码并写入 Redis |
| POST | `/user/login` | 无 | 验证码登录并返回用户 Token |
| GET | `/user/me` | 用户 Token | 查询当前身份 |
| POST | `/voucher/seckill` | 管理员 Token | 创建秒杀券并初始化 Redis 库存 |
| POST | `/voucher-order/seckill/{id}` | 用户 Token | 执行 Lua 资格预占 |
| GET | `/voucher-order/seckill/{id}/status` | 用户 Token | 查询异步订单状态 |
| GET | `/metrics/seckill` | 无 | 获取结构化秒杀指标 |
| GET | `/metrics/baseline` | 无 | 获取上一轮 AI 诊断基线 |
| GET | `/metrics/ai/analyze` | 无 | 触发 AI 诊断 |
| GET | `/admin/seckill/{id}/stats` | 无 | 查看券、队列和库存状态 |
| POST | `/admin/seckill/{id}/stop` | 管理员 Token | 紧急停止秒杀 |
| POST | `/admin/seckill/{id}/resume` | 管理员 Token | 按数据库库存恢复秒杀 |
| POST | `/admin/dead-letter/replay` | 管理员 Token | 原子重放死信 |
| POST | `/admin/reconcile/trigger` | 管理员 Token | 手动触发库存对账 |
| GET | `/admin/health/queue` | 无 | 查看 Pending 与死信状态 |
| POST | `/admin/metrics/reset` | 管理员 Token | 重置压测指标 |
| GET | `/actuator/prometheus` | 无 | Prometheus 采集入口 |

用户接口通过 `authorization: <token>` 传递身份；受保护的管理接口通过 `X-Admin-Token: <ADMIN_TOKEN>` 鉴权。

## 监控与告警

1. 使用根目录的 `prometheus.yml` 启动 Prometheus。
2. 同目录放置 `alert.rules.yml`。
3. 在 Grafana 导入 `src/main/resources/grafana-dashboard-seckill.json`。
4. 访问应用自带的 `dashboard.html` 查看运行指标并触发诊断。

告警覆盖消费者退出、心跳超时、消费停滞、订单成功率过低、基础设施失败率过高和持续库存不一致。

## 回归测试

应用和 Redis 启动后执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/regression.ps1 `
  -AdminToken $env:ADMIN_TOKEN
```

脚本真实验证验证码登录、秒杀券创建、Lua 预占、重复请求拦截、Stream 异步落库、原子死信补偿与重放、库存对账、指标采集和 AI 诊断入口。配置有效的 `DEEPSEEK_API_KEY` 后可增加 `-RequireAiDiagnosis`，要求 AI 返回非 `UNKNOWN` 状态。

如果 `redis-cli` 位于 WSL，可增加 `-RedisDistro Ubuntu-22.04`；要把测试数据隔离到专用 Redis 逻辑库，可增加 `-RedisDatabase 15`，并让应用使用相同的 `spring.redis.database`。

## JMeter 压测

压测计划与令牌准备方式见 [benchmark/README.md](benchmark/README.md)。默认模型为 2000 个用户竞争 400 份库存。TPS 和响应时间依赖机器、数据库、Redis 和网络环境，仓库不把单机结果当作通用性能承诺。
