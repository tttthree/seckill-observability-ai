# V2-6.1 / V2-7 修复与验收记录

日期：2026-10-02。基线：ea74646c1f04761fd55142b58a659bd4588ae701。

> **该文件为历史验证记录，当前架构请以 [README.md](../README.md) 与 [ARCHITECTURE.md](../ARCHITECTURE.md) 为准。**
> 文中描述的 DLQ replay / recovery marker / HELD-LEGACY 双轨协议、claim citation grounding 等能力已在后续剪枝中移除：
> 最终 Resume-Lite 版本不提供自动重放、自动补偿或自动恢复，`DEAD_LETTER` 事件保持 `OPEN` 等待人工核查。
> 文中出现的历史文件（如 `src/main/resources/replay-dead-letter.lua`、`ai-diagnosis-service/services/claim_grounding.py`）在当前版本已不存在。

## 实现

| 项目 | 收口方式 |
|---|---|
| A action priority | evidence 重排前截断 candidate_actions；重排和动作过滤共用候选集。第六条动作引用多个证据不改变 accepted 集合或 dropped_actions。删除未使用 GroundingOutcome。 |
| B1 停启 | 独立 active key；stop 不改 stock；resume 仅从 DB 起止时间补 metadata，不覆盖库存。stats 提供五种 activity 状态。 |
| B2 时间闸门 | 创建券 MSET 写 stock/active/begin/end；时间转 epoch millis；Java 传 nowMillis；Lua 先判活动，再判库存/资格，各拒绝原因独立返回码。 |
| B3 quarantine | XACK + DLQ HELD + 删除 retry，不 INCRBY、不 SREM；保留原预占，晚 DB 提交不引发自动回补。 |
| B4 replay | HELD 不再次预占；HELD/LEGACY 重放均先 SADD recovery orderId，再 XDEL/XADD；历史无标记或 LEGACY 仍重新预占；未知标记拒绝。Runbook 版本 2→3。 |
| B5 恢复 | DLQ 无该券 + recovery pending set 可确认为空 + Redis stock 存在 + DB 券/库存存在 + 两端一致才 resolve。扫描超限/null/不可识别记录及查询异常保持 OPEN。 |
| B6 对账 | missing 用 null 快照和明确描述，两阶段确认，不作零库存。低频分页兜底仅标脏：默认 30 分钟/100 券，页大小硬限制 1–1000；末页重置游标，失败不推进。 |
| B7 健康 | main 心跳仅消费循环刷新；Pending 及初始化不刷新。新增 pending Gauge；停滞告警要求 successAge>60s AND UP AND pending>0（毫秒值位于 AND 左侧）。 |

## 验证

- Java：JDK 11.0.15.1，`mvn -o test`，172 tests，0 failures/errors/skipped。
- Python：在 ai-diagnosis-service 下运行 `.venv\Scripts\python -m pytest`，175 passed，2 live tests deselected；已有 Starlette/AnyIO 废弃提示 1 条。
- Lua：`python3 scripts/test_lua_contract.py`，11 tests（含多组 subTest），通过。使用 WSL 已有 liblua5.1 和 Python 标准库，执行实际 Lua 脚本配内存 Redis 命令替身，不安装依赖、不连接业务 Redis。
- Lua 覆盖 before/at begin、at/after end、active=0、metadata missing/invalid、stock=0、duplicate；拒绝分支断言库存/资格/流不变；stop 后库存增加仍拒绝。
- 覆盖 HELD 隔离及晚提交安全属性、重复隔离、HELD 重放不 double reserve、LEGACY 重放/拒绝、重复重放。
- Java 覆盖 stop/resume 不覆盖库存、创建 metadata、返回码与服务端时间、DLQ 恢复的正反条件、missing≠zero、dirty 之外发现及分页上限/游标、Pending 不刷新 main 心跳、健康阈值和 PromQL/Gauge。
- 未运行真实 DeepSeek、破坏性故障注入、现有在线回归脚本或新压测。benchmark 旧结果不作为 V2-7 压测验收。

## 冻结边界

> 历史记录：本节冻结的是当时的契约版本（IncidentContext v2-2.1、prompt_version=v2-6.1）；当前版本已为 IncidentContext v3.0 / prompt_version=v3.1，见 [ARCHITECTURE.md](../ARCHITECTURE.md) §8～§11。

Java 11、Spring Boot 2.7.18、MyBatis-Plus 3.4.3、Redis Stream、MySQL 条件扣减/唯一约束、Incident open_key 均保留。IncidentContext v2-2.1、DiagnosisResult、evidence_validator、V2-4 Java AI client、V2-5 检索、prompt_version=v2-6.1 及单次模型调用不变。

未实现 Dashboard、诊断持久化、操作历史、审计表、NLI/verifier、第二次模型调用或新分布式框架。claim citation 只保证结论引用了真实 accepted evidence，不构成自然语言语义蕴含的形式化证明。

## 部署及剩余风险

> 历史记录：本节关于 HELD 保留预占与 recovery marker 的风险描述针对的是当时尚未剪枝的实现；当前版本不提供 replay / recovery marker，`DEAD_LETTER` 保持 `OPEN` 等待人工核查。

- 既有券 metadata 缺失时 fail closed，管理员 resume 可补齐；resume 不补库存。缺库存必须人工核查。
- DB LocalDateTime 按 JVM 默认时区转 epoch；各实例需统一时区，例如 `-Duser.timezone=Asia/Shanghai`。
- HELD 主动保留库存/资格，故障隔离期间可售库存会减少；取消、释放 reservation 不在本轮范围。
- 恢复同时使用按订单的 recovery marker 和券级库存一致性。标记无 TTL，只在事务代理返回成功或确认同 orderId 已存在后清除；再次隔离不清除。Redis 标记丢失/被外部误删仍会削弱恢复证明；并发读取不是跨 Redis/DB 的事务快照。
- Lua 离线测试证明正常命令的状态迁移，不证明 Redis 持久化、宕机恢复或命令类型错误的行为。Redis Lua 运行时命令错误不回滚此前写入，仍需保持 key 类型及存储运行条件正确。
- 券创建仍是 MySQL 事务与 Redis 写入组合，不具备跨存储原子提交；本轮未引入分布式事务或重建机制。
- main heartbeat 仍为主消费线程共享；不提供逐线程存活或尚未投递消息积压监控。兜底扫描覆盖周期随券数量增长。

实现遵循冻结方案；额外保守处理未知 replay 标记、null/非法 DLQ 扫描结果。Lua 验证采用离线解释器，不宣称已完成真实 Redis/MySQL 故障演练。

## Changed files

V2-6.1：

- ai-diagnosis-service/services/diagnosis_service.py
- ai-diagnosis-service/services/claim_grounding.py
- ai-diagnosis-service/tests/test_claim_grounding.py

V2-7：

- README.md
- ARCHITECTURE.md
- docs/V2-7-HARDENING.md
- ai-diagnosis-service/README.md
- ai-diagnosis-service/runbooks/rb-dead-letter-retry-exhausted.yaml
- alert.rules.yml
- scripts/regression.ps1
- scripts/test_lua_contract.py
- src/main/java/com/hmdp/config/SeckillProperties.java
- src/main/java/com/hmdp/constant/RedisConstants.java
- src/main/java/com/hmdp/controller/AdminController.java
- src/main/java/com/hmdp/monitor/HealthMetricsExporter.java
- src/main/java/com/hmdp/monitor/IncidentDetector.java
- src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java
- src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java
- src/main/java/com/hmdp/utils/SeckillActivity.java
- src/main/resources/application-template.yaml
- src/main/resources/seckill.lua
- src/main/resources/dead-letter.lua
- src/main/resources/replay-dead-letter.lua
- src/test/java/com/hmdp/controller/AdminActivityTest.java
- src/test/java/com/hmdp/monitor/ConsumerHealthIndicatorTest.java
- src/test/java/com/hmdp/monitor/IncidentDetectorTest.java
- src/test/java/com/hmdp/service/impl/SeckillActivityGateTest.java
- src/test/java/com/hmdp/service/impl/VoucherActivityTest.java
- src/test/java/com/hmdp/service/impl/VoucherOrderServiceImplReconcileTest.java

本地已有 diagnosis.json / v25-final.json 不修改、不提交。

## V2-7 code review 收口

新增 `seckill:dead:recovery:{voucherId}` Set，成员为 replay 的 orderId。HELD 与 LEGACY 均在同一 Lua 中确认 DLQ 条目存在、SADD marker、XDEL、XADD；重复重放不存在条目时不新增 marker。消费者在 DB 事务代理返回后 SREM；已存在同 orderId 时按幂等成功 SREM。主消费入口同样先确认 orderId，避免已提交订单在 DB 零库存时被误当 StockException。DuplicateKeyException 必须确认这个 orderId 存在，不能仅凭同用户/券唯一键冲突清 marker；清理异常或结果不明确不 ACK，留待重试。

反例验证：Redis=0 / DB=0 且 replay 已移出 DLQ，只要 marker 尚在就保持 OPEN；marker 清除后才能结合无 DLQ/库存一致关闭。多个订单只完成一个仍保持 OPEN，replay 再次失败/隔离保留 marker。Redis recovery SCARD 异常或 null 结果均不关闭事件。

ConsumerStalled 的左操作数为 success_heartbeat_age，确保告警 value 是实际毫秒；stats.redis_stock 缺失显示 MISSING 而不是 0。未修改 IncidentContext、Python 生产代码或 Java AI contract。完整 Java 172、Lua 11、Python 175 测试通过，2 live 测试排除；未做 runtime smoke。

本轮另新增 src/test/java/com/hmdp/service/impl/DeadRecoveryPendingTest.java。
