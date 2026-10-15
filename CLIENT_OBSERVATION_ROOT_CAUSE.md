# Client E2E 与 Server E2E 差异：观测与根因定位

日期：2026-09-21
阶段：第二轮性能优化第三阶段（只观测、不优化）

## 结论先行

本阶段没有修改 Publisher、Outbox、Consumer、ACK、数据库、Redis、Lua、Tomcat、JVM、HTTP 行为或原始 benchmark 参数；只增加了 profiling 日志和一个保持原控制流的观测副本。

正式观测样本为 100 个 accepted request，100/100 完成，0 timeout：

| 指标 | Avg | P50 | P95 | P99 | Max |
|---|---:|---:|---:|---:|---:|
| Client E2E（C7-C0） | 5375.580 ms | 5386 ms | 5398 ms | 5400 ms | 5400 ms |
| Server T0-T11 | 683.190 ms | 678.497 ms | 1173.175 ms | 1204.121 ms | 1212.052 ms |
| T11 → 第一次 Result API R0 | 4502.860 ms | 4496 ms | 4974 ms | 5003 ms | 5039 ms |
| Result API R3-R0 | 2.883 ms | 2.832 ms | 3.585 ms | 4.421 ms | 4.582 ms |

主要根因不是 Result API 慢，也不是 polling executor 调度慢，而是当前 benchmark 的批次屏障：所有 POST worker 的 Future 都收集完成后，才统一提交所有 polling task。由于 Client E2E 的起点是每个请求自己的 POST 开始时刻，较早完成的请求会把等待其他 POST 请求完成的时间计入 E2E。

这次 profiling run 中，该批次等待在客户端表现为 `POST response → first poll` 平均 4860.060 ms，在跨 JVM wall-clock 关联中表现为 `T11 → first R0` 平均 4502.860 ms。两者共同解释了 Client E2E 与 Server T0-T11 之间约 4.7 秒的主要差异。

> 本次增加了服务端和客户端 profiling，故这些数值只用于阶段性归因，不能直接作为无 profiling 基线的严格性能回归结论。用户此前报告中的 Client P99 5361 ms 与 Server T0-T11 P99 1747.713 ms 仍需按同一批次、同一 profiling 开关和同一环境复测后再做数值对比。

## 1. 观测范围与冻结项

本阶段冻结以下内容：

- Publisher：`core=4`、`max=8`、`queue=500`。
- Outbox poll、batch、Consumer `4/8/32`、ACK mode。
- Hikari、MySQL schema/index、Redis、Lua、Tomcat、JVM 参数。
- Result API 的业务语义和返回值。
- 原始 benchmark 的 worker 数、HTTP client、timeout、50 ms polling sleep、批次屏障和 E2E 起点。

新增内容只有：

- 服务端 `ResultApiTrace`：记录 R0-R3 和 polling sequence。
- 现有 `SeckillTrace.finish` 的 `finishedEpochMs`，用于跨 JVM wall-clock 关联。
- `ClientObservationBenchmark`：原始 benchmark 的观测副本，记录 C0-C7、每次 GET 和 polling executor 状态。

原始 benchmark 未修改，见 [`SeckillBenchmark.java`](docs/performance/2026-09-20-seckill/SeckillBenchmark.java)。源码审计见 [`benchmark-audit.md`](docs/performance/2026-09-21-client-observation/benchmark-audit.md)。

## 2. Client E2E 与 Server E2E 的统计口径

### Client E2E

原始 benchmark 对每个 accepted request 保存 POST 开始前的 `startedEpochMillis`，在 polling 观察到 `SUCCESS` 后计算：

```text
Client E2E = currentTimeMillis() - startedEpochMillis
```

因此它包含：

1. POST task preparation / queue；
2. POST HTTP 请求；
3. 等待所有 POST Future 完成的共同批次屏障；
4. polling task 提交和 executor 调度；
5. GET HTTP 请求；
6. 客户端解析 SUCCESS。

它不是“该 request 独立开始 polling 后的轮询时长”。

### Server E2E

Server T0-T11 是同一应用 JVM 内由 `SeckillTrace` 记录的业务链路相对 `nanoTime` 时长。当前正式样本统计为：Avg 683.190 ms、P99 1204.121 ms。

跨 JVM 关联只使用 `finishedEpochMs` 与客户端/Result API 的 wall-clock；没有直接相减不同 JVM 的 `nanoTime`。

## 3. Benchmark 控制流与批次屏障

正式观测副本保留原始流程：

```text
POST fixed pool
  → 收集全部 POST Future
  → pollCompletions(accepted)
  → polling fixed pool
  → 每个 request 首次 GET，非 SUCCESS 才 sleep(50 ms)
```

原始源码中，POST Future 收集位于 [`SeckillBenchmark.java:61-65`](docs/performance/2026-09-20-seckill/SeckillBenchmark.java)，`pollCompletions` 直到 [`SeckillBenchmark.java:83`](docs/performance/2026-09-20-seckill/SeckillBenchmark.java) 才开始。正式观测中所有 request 都已经是最终 SUCCESS，因此 `50 ms` sleep 没有进入有效路径。

这意味着一个早期完成的 POST，必须等待最慢的 POST worker 收尾后才有机会发出第一次 Result API 请求；这正是本阶段发现的主要 measurement artifact / benchmark control-flow artifact。报告记录该事实，但不修复它。

## 4. Client C0-C7 观测结果

### 4.1 分段耗时

| Client 段 | 含义 | Avg | P50 | P95 | P99 | Max |
|---|---|---:|---:|---:|---:|---:|
| C1-C0 | POST 请求构造及提交前准备 | 13.857 ms | 13.738 | 16.047 | 16.262 | 16.298 |
| C2-C1 | POST HTTP | 242.545 ms | 247.188 | 332.643 | 342.752 | 344.792 |
| C3-C2 | POST response 到 polling 系统提交/首个 poll 的等待 | 4860.060 ms | 4853 | 4966 | 5000 | 5034 |
| C4-C3 | polling executor submit 到 worker 开始 | 0.471 ms | 0.340 | 1.070 | 1.836 | 1.868 |
| C7-C6 | 最后一次 GET 返回到 SUCCESS 解析 | 0.020 ms | 0 | 0 | 0 | 2 |

`C7-C6` 使用 wall-clock 毫秒，因此大量 0 是时间分辨率结果，不代表解析路径不存在；它没有显示出可解释 Client E2E 差距的客户端解析开销。

### 4.2 Poll 次数与 50 ms sleep

| 指标 | 结果 |
|---|---:|
| accepted request | 100 |
| completed request | 100 |
| timed out | 0 |
| poll count distribution | `1 => 100` |
| 有效 inter-poll interval 样本 | 0 |

所有请求第一次 GET 即返回 SUCCESS，没有请求执行第二次 GET，因此本样本无法从实际间隔分布估计 `Thread.sleep(50)` 的贡献；但可以确定它对这 100 个正式请求的 E2E 没有实际贡献。

## 5. Result API R0-R3 观测结果

服务端在不改变 `service.result(requestId)` 和响应语义的前提下记录：

- R0：controller 入口 wall-clock / nanoTime；
- R1：调用 `service.result` 前；
- R2：调用完成后；
- R3：返回响应前。

| Server 段 | 含义 | Avg | P50 | P95 | P99 | Max |
|---|---|---:|---:|---:|---:|---:|
| R3-R0 | Result API 服务端总耗时 | 2.883 ms | 2.832 | 3.585 | 4.421 | 4.582 |
| R2-R1 | reservation hash Redis lookup 段 | 2.883 ms | 2.831 | 3.585 | 4.408 | 4.582 |
| R3-R2 | 返回对象/响应前极短区间 | 0.240 ms | 0 | 1 | 1 | 1 |

`GET /api/seckill/result/{requestId}` 只读取 Redis reservation hash 中的状态、userId、orderNo，不查询 MySQL。此次 formal 样本的 Result API 服务端耗时在毫秒级低个位数，不能解释约 5 秒的 Client E2E。

当前日志没有记录 R3 的 wall-clock，因此不能可靠计算跨 JVM 的 `C6-R3`。客户端 GET wall latency 与服务端 R3-R0 不能用两个 JVM 的 `nanoTime` 直接相减；该不可计算项已保留为不确定性，而不是用估算值填补。

## 6. T11 → 第一次 R0 的跨 JVM 关联

100 个 accepted request 均有：

- 一条客户端 request trace；
- 一条或多条 Result API trace，本次全部为一次；
- 一条包含 `finishedEpochMs` 的 `seckill-profile`。

以 requestId 关联，并使用 wall-clock 计算 `first R0 - T11`：

| 指标 | Avg | P50 | P95 | P99 | Max |
|---|---:|---:|---:|---:|---:|
| T11 → first R0 | 4502.860 ms | 4496 | 4974 | 5003 | 5039 |

该结果与 `C3-C2` 的 4860.060 ms 一致地指向：业务链路完成后，客户端没有立即发出第一次结果查询，而是在 POST 批次收尾后才开始 polling。由于两个 JVM 的 wall-clock 只有毫秒级、且机器时钟并未提供更高精度同步保证，该关联用于根因定位，不用于微秒级拆账。

## 7. Polling executor 与 HTTP 观测

### 7.1 Polling executor

正式样本的 executor sampler 记录只有 3 个采样点，因为 100 个第一次 GET 很快完成。最大观测值：

| 指标 | Max |
|---|---:|
| pool size | 100 |
| active count | 100 |
| queue size | 0 |
| completed task count | 4 |
| largest pool size | 100 |
| rejected count | 0 |

没有观察到 polling executor queue、拒绝或 worker starvation。`C4-C3` P99 仅 1.836 ms，故 polling executor 调度不是主要根因。

### 7.2 HTTP client / connection pool

源码只构造了 JDK `HttpClient`，没有显式配置 custom executor、最大连接数、per-host 连接数或 keep-alive 参数。POST client 保持 HTTP/1.1、connect timeout 5 s；GET client 保持默认 version、connect timeout 5 s；POST request timeout 10 s，GET request timeout 5 s。

客户端每次 GET 的 wall latency：Avg 258.901 ms、P50 267.523 ms、P95 282.840 ms、P99 284.777 ms、Max 285.549 ms。这个数字说明 GET 端到端仍有约 259 ms 的客户端观测耗时，但当前 trace 没有把连接获取、socket、Tomcat 排队、网络传输拆开，因此不能把它归因成某一种 HTTP connection-pool 限制。

可以确认的是：该耗时发生在每个请求已等待约 4.86 秒之后，而且每个请求只发出一次 GET；它是次要的可观测贡献，不是约 4.5 秒 gap 的根因。

## 8. Gap 分解

按平均值看，Client E2E 约可由以下部分组成：

```text
13.857  C1-C0  POST preparation
242.545  C2-C1  POST HTTP
4860.060  C3-C2  batch barrier → polling submission
0.471  C4-C3  polling executor scheduling
258.901  C6-C5  GET client wall latency
0.020  C7-C6  SUCCESS observation
≈5375.854 ms
```

该分解与观测到的 Client E2E Avg 5375.580 ms 在时间分辨率和各段边界差异范围内一致。它说明约 90% 的平均 Client E2E 由 POST 批次屏障相关等待占据，而非 Result API 内部处理。

注意：上表是平均值分解，不能把各段 P99 简单相加来得到严格的 P99；P99 仍以原始样本 `client-request-trace.csv` 和 `client-poll-trace.csv` 为准。

## 9. 十个问题的明确回答

1. **是否是 polling executor 调度慢？** 不是主要原因。`C4-C3` P99 1.836 ms，队列最大 0，拒绝 0。
2. **是否是 50 ms polling sleep？** 本次不是。100 个请求全部第一次 GET 成功，实际 inter-poll interval 没有有效样本。
3. **是否是 POST batch barrier？** 是主要原因。原始控制流等待所有 POST Future 后才提交 polling；`C3-C2` 平均 4860.060 ms。
4. **是否是 HTTP 连接获取或连接池限制？** 当前证据不足以归因。源码未配置可观测的连接池指标；GET client wall latency 平均 258.901 ms，但无法拆成连接获取、网络和服务端排队。
5. **是否是 Result API 本身慢？** 不是。R3-R0 P99 4.421 ms，Redis lookup P99 4.408 ms。
6. **是否是 Redis 查询慢？** 不是导致秒级 gap 的原因。Redis lookup 平均 2.883 ms。
7. **是否是客户端 SUCCESS 解析慢？** 不是。C7-C6 最大 2 ms，且为 wall-clock 毫秒分辨率。
8. **是否是 Server T0-T11 本身覆盖不完整？** 当前 T0-T11 与 T11 wall-clock 已成功关联；它只表示服务端业务链路，不包含客户端批次屏障，这是两个统计口径的差异。
9. **是否存在请求关联缺失或重复？** 没有。正式样本 100 accepted、100 client traces、100 successful Result API traces、100 T11 关联，均一对一。
10. **当前 Client E2E 是否适合作为单请求完成时延？** 不完全适合。它保留了原 benchmark 的定义，但该定义把共同批次屏障计入每个请求；本阶段只记录并报告影响，没有修改 benchmark。

## 10. 根因排序

| 排名 | 根因/因素 | 证据 | 影响判断 |
|---:|---|---|---|
| 1 | POST 完成后的共同批次屏障 | `C3-C2` Avg 4860.060 ms；T11→R0 Avg 4502.860 ms | 主要根因，秒级 |
| 2 | GET 客户端端到端耗时 | GET wall Avg 258.901 ms | 次要贡献，百毫秒级；具体子原因未拆出 |
| 3 | POST HTTP 本身 | `C2-C1` Avg 242.545 ms | Client E2E 的正常组成部分，不解释 Client/Server 的主要 gap |
| 4 | polling executor 调度 | `C4-C3` P99 1.836 ms，queue=0 | 非根因 |
| 5 | 50 ms sleep | 所有请求 poll count=1 | 本样本无贡献 |
| 6 | Result API / Redis lookup | R3-R0 P99 4.421 ms | 非根因 |
| 7 | 客户端 SUCCESS 解析 | C7-C6 Max 2 ms | 非根因 |

## 11. 观测产物

- [`analysis-summary.csv`](docs/performance/2026-09-21-client-observation/analysis-summary.csv)：汇总指标。
- [`client-request-trace.csv`](docs/performance/2026-09-21-client-observation/client-request-trace.csv)：100 个 accepted request 的 C0-C7 派生字段。
- [`client-poll-trace.csv`](docs/performance/2026-09-21-client-observation/client-poll-trace.csv)：每次 Result API GET、poll sequence、状态和间隔。
- [`poll-executor.csv`](docs/performance/2026-09-21-client-observation/poll-executor.csv)：polling executor 采样。
- [`formal-interface.csv`](docs/performance/2026-09-21-client-observation/formal-interface.csv)：正式观测副本的原始接口汇总。
- [`formal-accepted.csv.e2e.csv`](docs/performance/2026-09-21-client-observation/formal-accepted.csv.e2e.csv)：保持原始 E2E 统计口径的结果。
- [`app.stdout.log`](docs/performance/2026-09-21-client-observation/app.stdout.log)：应用 profiling 日志，包含 `result-profile` 与 `seckill-profile`。

## 12. 后续动作建议（本阶段不执行）

本阶段只完成归因，不改变 benchmark 或生产参数。若下一阶段需要验证“真实单请求完成时延”，应另立阶段并明确选择一种统计口径：

- 保留当前批次屏障，继续把它作为压力批次完成观测；或
- 设计独立的 per-request polling 观测，单独报告它与当前 Client E2E，避免混用。

在作出该决策前，不应根据本次 profiling 数值直接调整 Consumer、HTTP 或 Result API 参数。
