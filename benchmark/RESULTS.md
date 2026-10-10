# 压测结果与历史对比

测试执行于代码基线 `0ac054c1eb97e6919c84a56268813c1be8c7d611`；**该版本之后至最终 HEAD 未修改 Java / Python 运行代码**（期间变更仅涉及 README、ARCHITECTURE、AI 服务说明与 benchmark 结果文档）。因此以下结果仍对应当前运行代码，并非在最终版本上重新执行。

## 测试模型

- 工具：Apache JMeter 5.6.3
- 用户数：2000（对应 2000 个线程，每个线程一个独立登录令牌）
- 库存：400
- Ramp-up：10 秒
- 每个用户请求次数：1
- 运行次数：3
- 每轮前执行 `POST /admin/metrics/reset`，并新建一张 `stock=400` 的秒杀券

## JMeter 汇总

| 指标 | Run 1 | Run 2 | Run 3 | Median |
|---|---:|---:|---:|---:|
| Avg | 46.77 ms | 74.65 ms | 58.10 ms | **58.10 ms** |
| P95 | 207 ms | 293.9 ms | 230.6 ms | **230.6 ms** |
| P99 | 294 ms | 533.9 ms | 383.9 ms | **383.9 ms** |
| TPS | 230.89 req/s | 236.41 req/s | 238.35 req/s | **236.41 req/s** |
| Error（JMeter 客户端） | 0% | 0% | 0.05%（1/2000） | 0% |
| Error（服务端 5xx/4xx） | 0 | 0 | 0 | 0 |

## 业务结果

| 指标 | Run 1 | Run 2 | Run 3 |
|---|---:|---:|---:|
| reserve_success | 400 | 400 | 400 |
| order_success | 400 | 400 | 400 |
| stock_fail | 1600 | 1600 | **1599** |
| duplicate_request | 0 | 0 | 0 |
| infra_fail | 0 | 0 | 0 |
| reconcile_mismatch | 0 | 0 | 0 |
| pending | 0 | 0 | 0 |
| dead_letter | 0 | 0 | 0 |
| consistent | true | true | true |

数据库侧独立复核（对三张压测券）：

```text
voucher 16 -> 400 orders, stock 0
voucher 17 -> 400 orders, stock 0
voucher 18 -> 400 orders, stock 0
(user_id, voucher_id) 重复订单数 = 0
```

### Run 3 的 `stock_fail = 1599` 说明（如实记录，未重跑美化）

Run 3 的原始 JTL 中共 2000 条样本，其中 **1999 条返回 HTTP 200**，**1 条为 JMeter 客户端错误**：

```text
Non HTTP response code:    java.net.BindException
Non HTTP response message: Address already in use: connect
```

即该线程在建立本地 TCP 连接时因**本机临时端口耗尽**而失败，**该请求从未到达服务端**。因此：

- 服务端只收到 1999 个请求：400 个预占成功 + 1599 个库存不足；
- 服务端 5xx / 4xx 错误数为 **0**；
- 数据库侧三张券均为 **400 单、库存 0、无重复订单**，与 `reserve_success = order_success = 400` 一致。

这是单机 JMeter 压测侧的已知限制（2000 并发短连接），不是应用侧故障。**未删除或重跑该轮以美化结果。**

## 与剪枝前版本的对比

剪枝前记录为 Avg 75.13 ms / P95 621 ms / P99 730.95 ms / TPS 246.82 req/s。当前 Resume-Lite 的 Avg/P95/P99 均更低，TPS 略低（−4%）。单机短压测存在明显的运行间波动（Run 1 与 Run 2 的 Avg 相差 60%），两组数据并非严格控制变量的对照实验，**不足以得出"剪枝改善了性能"的结论**。

## 结果边界

以上结果用于验证当前版本在指定本机硬件和测试模型下的功能正确性与回归表现，不构成生产环境容量或性能承诺。复现方式见 [README.md](README.md)。

本轮压测由 `target/load-test/run-bench.ps1`（一次性执行器，位于 gitignored 的 `target/` 下，不提交）驱动，原始 JTL 与 HTML 报告位于 `target/load-test/`。
