# 当前版本压测结果

## 测试模型

- 工具：Apache JMeter
- 用户数：2000（对应 2000 个线程）
- 库存：400
- Ramp-up：10 秒
- 每个用户请求次数：1
- 运行次数：3

## JMeter 汇总

| 指标 | Run 1 | Run 2 | Run 3 | Median |
|---|---:|---:|---:|---:|
| Avg | 74.59 ms | 75.13 ms | 281.83 ms | 75.13 ms |
| P95 | 474.95 ms | 621 ms | 1211 ms | 621 ms |
| P99 | 655.99 ms | 730.95 ms | 1280 ms | 730.95 ms |
| TPS | 246.82 req/s | 242.16 req/s | 272.00 req/s | 246.82 req/s |
| Error | 0% | 0% | 0% | 0% |

## 业务结果

三轮结果一致：

```text
reserve_success = 400
order_success   = 400
stock_fail      = 1600
pending         = 0
dead_letter     = 0
consistent      = true
```

- 400/400 个 Redis 资格预占均完成 MySQL 异步落库，Redis→MySQL 转化率为 100%。
- 其余 1600 个请求均在 Redis 库存层拦截。
- 未发生超卖，Pending、死信及基础设施异常均为 0。

## 结果边界

以上结果用于验证当前版本在指定本机硬件和测试模型下的功能正确性与回归表现，不构成生产环境容量或性能承诺。复现方式见 [README.md](README.md)。
