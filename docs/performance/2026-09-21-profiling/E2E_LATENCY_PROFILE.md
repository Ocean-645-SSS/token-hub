# TokenHub 秒杀链路端到端延迟剖析

## 结论

本轮仅做链路剖析，没有实施第二轮性能参数或业务代码优化。隔离压测表明，100 个成功订单的端到端延迟主要消耗在：

1. Outbox 发布器从消息写入到被 claim 的等待时间；
2. RabbitMQ confirm 之后到消费者实际收到消息的等待时间。

MySQL 最终订单事务不是当前 8 秒级 E2E 延迟的主要来源。当前证据不足以把瓶颈归因于单一组件的 CPU 或连接池耗尽；需要后续增加 RabbitMQ、线程池、Hikari 和系统资源的持续采样后再做第二轮优化。

## 测试口径

- 应用：最新构建的 TokenHub JAR，开启 `-Dtokenhub.profiling.enabled=true`。
- 依赖：Docker 中的 MySQL 8.0.33、Redis 7、RabbitMQ 3-management；应用通过宿主机端口访问。
- 数据库：临时账号 `tokenhub_bench` 仅拥有 `tokenhub.*` 的 `SELECT/INSERT/UPDATE/DELETE` 权限；未修改现有表结构。
- 客户端：`SeckillBenchmark`，请求成功后独立轮询 `/api/seckill/result`；轮询线程池最多 100 个 worker，每次未完成轮询间隔 50ms。
- 服务端阶段：使用 `System.nanoTime()` 记录 T0-T11，trace 仅在内存中保存并在订单完成时移除，不写数据库。
- 本轮不把客户端首次建连、JWT 首次生成等冷启动开销计入正式测量；worker 连接和 JWT 在计时前预热。

运行环境补充：JDK 17.0.18 LTS，Spring Boot 3.3.13；MySQL 8.0.33、Redis 7-alpine、RabbitMQ management 镜像均运行在 Docker，应用和压测客户端运行在宿主机。由于当前 Windows 环境的 CIM 查询权限受限，本轮没有可靠采集 CPU 核数和物理内存，不能编造这两项数据。

阶段含义：T0 请求开始，T1 Redis/Lua 返回，T2 reservation 写入，T3 outbox INSERT，T4 HTTP 返回前，T5 publisher claim，T6 RabbitMQ publish 返回，T7 publisher confirm ACK，T8 consumer 收到消息，T9 幂等检查完成，T10 MySQL 订单事务完成，T11 Redis 订单结果更新完成。

## 实测结果

### 客户端观测的 E2E

| 场景 | 并发 | 总请求 | 成功 | 失败 | 平均 E2E (ms) | P50 (ms) | P95 (ms) | P99 (ms) | 最大 (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Test A，单请求基线 | 1 | 1 | 1 | 0 | 2216.0 | 2216 | 2216 | 2216 | 2216 |
| Test B，10 并发，非隔离 | 10 | 99 | 99 | 0 | 6886.9 | 7735 | 9673 | 9712 | 9712 |
| Test C，100 并发，隔离 | 100 | 100 | 100 | 0 | 6150.4 | 6622 | 8873 | 9023 | 9056 |

Test B 与前一轮活动共用过同一条异步链路，不能用于精确比较，因此不作为性能结论依据。Test C 运行前确认 RabbitMQ `seckill.order.queue` 为 `0 ready / 0 unacked`，是本轮用于阶段分析的隔离样本。

### Test C：服务端 T0-T11 阶段耗时

以下为 100 个成功订单的阶段增量，单位毫秒；P99 按每个阶段独立计算。

| 阶段 | 含义 | Avg | P50 | P95 | P99 | Max |
|---|---|---:|---:|---:|---:|---:|
| T0-T1 | Redis Lua 库存预扣 | 15.64 | 16.68 | 28.97 | 31.16 | 31.46 |
| T1-T2 | reservation 写入 | 11.97 | 11.94 | 19.71 | 31.25 | 31.26 |
| T2-T3 | outbox INSERT | 235.28 | 234.49 | 431.92 | 432.26 | 434.20 |
| T3-T5 | publisher 等待 claim | 3126.03 | 3013.92 | 5441.25 | 5623.85 | 5670.92 |
| T5-T6 | Rabbit publish 调用 | 5.50 | 0.64 | 1.11 | 1.25 | 481.75 |
| T6-T7 | Rabbit confirm ACK | 194.94 | 128.36 | 455.92 | 479.20 | 501.32 |
| T7-T8 | MQ 到 consumer 收到 | 2212.54 | 2659.19 | 2782.52 | 2797.08 | 2811.96 |
| T8-T9 | 消费端幂等检查 | 7.38 | 4.03 | 24.93 | 42.45 | 50.91 |
| T9-T10 | MySQL 订单事务 | 19.92 | 14.04 | 61.07 | 107.36 | 144.68 |
| T10-T11 | Redis 结果更新 | 6.41 | 3.64 | 20.10 | 29.79 | 55.95 |
| T0-T11 | 服务端 E2E | 5835.60 | 6351.05 | 8687.90 | 8836.90 | 8864.82 |

客户端 P99 为 9023ms，服务端 T0-T11 P99 为 8836.9ms，两者相差约 186ms。说明本样本中，客户端结果轮询有额外开销，但不是主要延迟来源。

## 瓶颈判断

- **最主要阶段：Outbox claim 等待。** P99 约 5.62s，远大于 outbox INSERT 本身的 P99 432ms。当前配置为轮询发布器，不能仅凭 200ms poll interval 解释全部等待，需要继续采样 publisher 线程调度、数据库查询耗时、批次空转和锁等待。
- **第二主要阶段：MQ 投递到消费者收到。** P99 约 2.80s。测试前队列为空，但本轮没有连续采集 ready/unacked、消费者线程池和 broker 指标，因此只能确认存在消费侧等待，不能进一步断言是 RabbitMQ broker、应用消费者线程池还是消息确认/调度造成。
- **MySQL 不是当前主瓶颈。** 订单事务 P99 约 107ms，远小于秒级 E2E；它仍需在更高并发和独立数据库指标下复核。
- **Redis Lua 与 Redis 更新不是主瓶颈。** Redis 预扣 P99 约 31ms，结果更新 P99 约 30ms。
- 当前没有采集 Hikari active/pending、Tomcat busy threads、RabbitMQ ready/unacked 时间序列和宿主机 CPU/IO，因此不能把瓶颈进一步归因到连接池、线程池或机器资源。

另外，对当前数据库执行的 outbox 查询 `EXPLAIN` 显示使用 `idx_message_status_retry`，但仍有 `Using filesort`。这说明查询存在可优化点，但本轮遵守“不调整参数/不修改核心逻辑”的要求，没有执行 DDL 或索引变更。

## 数据一致性边界

本轮 profiling 样本中，100 个成功请求最终均完成到 T11；应用停止前的 outbox 汇总为 `pending=0, published=300, confirmed=300, failed=0, retries=0`（包含本轮同一运行实例此前的样本），队列可排空。未观察到超卖、重复订单或 Redis 扣减后长期没有落库的样本。

这不是完整的故障注入测试：本轮没有主动杀进程、断开 RabbitMQ 或制造 MySQL 故障，因此不能据此证明异常恢复场景下绝对不丢单。正常路径下，Redis 预扣、outbox、RabbitMQ 和 MySQL 的最终结果是一致的。

## 下一轮建议（本轮不实施）

1. 给 publisher 增加 claim/query/publish/confirm 的独立计时，并持续采集 outbox pending 数量、批次大小和数据库锁等待；优先确认 T3-T5 的真实等待来源。
2. 采集 RabbitMQ `messages_ready`、`messages_unacknowledged`、consumer 数量、prefetch 和消费线程池队列，拆分 T7-T8 的 broker 等待与应用调度等待。
3. 同时采集 Hikari、Tomcat、JVM GC、CPU、磁盘 IO 和 MySQL performance schema，再决定是调整发布器调度、消费者并发，还是优化 outbox 索引/查询。

## 原始材料

- 应用 trace：`docs/performance/2026-09-21-profiling/app.log`
- stderr：`docs/performance/2026-09-21-profiling/app.err.log`
- 客户端结果：`docs/performance/2026-09-21-profiling/`
- trace 实现：`src/main/java/com/tokenhub/profiling/SeckillTrace.java`
