# 压测说明

`seckill-2000.jmx` 默认使用 2000 个线程，在 10 秒内发起一轮秒杀请求。每个线程从
`target/load-test/tokens.txt` 读取一个独立登录令牌，以覆盖真实的一人一单校验。

## 准备

1. 启动 MySQL、Redis 和应用。
2. 通过 `POST /voucher/seckill` 创建库存为 400 的秒杀券。
3. 生成压测令牌：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/seed-load-test-tokens.ps1 `
  -Users 2000 `
  -RedisDatabase 0
```

## 执行

```powershell
jmeter -n -t benchmark/seckill-2000.jmx `
  -JBASE_URL=http://127.0.0.1:8081 `
  -JVOUCHER_ID=<秒杀券ID> `
  -JTOKENS_FILE=target/load-test/tokens.txt `
  -l target/load-test/result.jtl `
  -e -o target/load-test/report
```

正式压测前调用 `POST /admin/metrics/reset` 清空旧指标。结果报告位于
`target/load-test/report/index.html`，该目录和 `.jtl` 文件不提交到 Git。
