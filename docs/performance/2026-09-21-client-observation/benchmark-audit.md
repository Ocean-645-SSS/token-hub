# Benchmark / Result API 口径审计

审计对象：`docs/performance/2026-09-20-seckill/SeckillBenchmark.java`。

- POST worker 使用 `Executors.newFixedThreadPool(concurrency)`；正式并发为 100。
- POST 使用一个共享 `HttpClient`：HTTP/1.1、connect timeout 5s；POST request timeout 10s。
- 每个成功 POST 记录的是 POST 开始前的 `startedEpochMillis`。
- 所有 POST worker 的 `Future` 在主线程收集完成后，才调用 `pollCompletions`（源码 61–83 行）。因此每个 request 的 polling 都存在一个共同的 POST 批次屏障。
- polling 使用 `Executors.newFixedThreadPool(min(accepted.size(), 100))`；正式样本为 100 个 worker。
- polling 使用另一个共享 `HttpClient`，connect timeout 5s，GET request timeout 5s；源码未配置自定义 executor、最大连接数、per-host 连接数或 keep-alive 参数。
- 每次 GET 返回非 SUCCESS 后执行 `Thread.sleep(50)`；正式 profiling 样本中所有成功 request 都在第一次 GET 返回 SUCCESS，因此没有有效的两次 polling 间隔样本。
- 原始 Client E2E 是 `System.currentTimeMillis() - startedEpochMillis`，包含 POST 开始、POST 完成后等待其它 POST future 的批次屏障、polling executor 提交等待、GET 和 SUCCESS 观察；它不是仅由该 request 独立驱动的连续 polling 时间。

服务端 `GET /api/seckill/result/{requestId}` 只读取 Redis reservation hash，不查询 MySQL。第三阶段新增的 `result-profile` 记录 R0–R3，现有 `seckill-profile` 增加 `finishedEpochMs` 用于跨 JVM wall-clock 关联。
