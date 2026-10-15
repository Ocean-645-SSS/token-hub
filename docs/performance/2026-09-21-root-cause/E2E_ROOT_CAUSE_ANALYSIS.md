# TokenHub 秒杀链路第二阶段根因定位

## 结论摘要

本轮只增加观测点和采样，没有修改 scheduler、RabbitMQ consumer、Hikari、batch、poll interval、prefetch，也没有创建索引。

在完成 warm-up 后重新执行 100 并发、100 个成功订单的隔离样本：

- 客户端 E2E P99：**8260ms**；服务端 T0-T11 P99：**8011.56ms**。
- Outbox INSERT 到 claim（P0→P5）P99：**5229.02ms**。
- Rabbit publish 到 confirm（M0→M1）P99：**293.68ms**。
- Confirm 到 listener 方法入口（M1→M3）P99：**2116.85ms**。
- Listener 业务处理本身（M4→M5）P99：**166.55ms**。

根因已经从黑盒缩小到两个具体位置：

1. **Outbox：默认单线程 scheduler 中串行执行 claim/publish。** 本次没有独立 publisher executor；`publishClaimed` 直接运行在 `scheduling-1`。两轮分别处理 30、70 条消息，单轮耗时 2511ms、3320ms。消息等待 scheduler 的 P99 为 2441.74ms，消息排队到 claim 的 P99 为 5229.02ms。
2. **RabbitMQ 后半段：只有 1 个 consumer，prefetch=250。** `messages_ready` 全程为 0，但 `messages_unacknowledged` 峰值为 46；说明消息已经被该 consumer 预取，延迟主要发生在 consumer 侧交付/串行处理前，而不是 broker 中 ready 队列堆积。业务方法本身只有 166.55ms P99，不能解释 2.12s 的 M1→M3。

Hikari 也观察到连接竞争，但不是两个黑盒等待的首要解释：连接池 `max=10`，采样中 active 峰值 10、waiting 峰值 70；与此同时 pending SELECT P99 仅 8.28ms、claim UPDATE P99 209.62ms。当前证据支持“HTTP 高并发期间存在连接池等待”，不支持“数据库查询本身造成 5 秒级等待”。

## 测试口径

- 应用开启 `-Dtokenhub.profiling.enabled=true`，使用 JDK 17.0.18、Spring Boot 3.3.13。
- MySQL 8.0.33、Redis 7、RabbitMQ 3-management 运行在 Docker；压测客户端和应用运行在宿主机。
- Test C：100 并发、100 个成功订单；开始前确认 RabbitMQ `ready=0 / unacked=0`、outbox `pending=0`，并先用 10 并发 warm-up。
- 客户端结果：`interface-warm-c.csv`；成功请求：`capture-warm-c.csv`；服务端 trace 从 `capture-warm-c.csv` 的 requestId 精确关联。
- RabbitMQ 采样：管理 API 每约 100ms 采样一次；活跃队列样本保存在 `rabbit-c.csv`。
- Hikari 采样：独立 daemon 线程每 100ms 读取 `HikariPoolMXBean`，避免占用 Spring 默认 scheduler。

阶段定义：

- Outbox：P0 INSERT 返回/提交完成，P1 scheduler 实际开始，P3/P4 pending SELECT 开始/完成，P5/P6 claim UPDATE 开始/完成，P7 提交 publisher，P8 publisher worker 入口，P9 Rabbit send 前。
- Rabbit/Consumer：M0 `convertAndSend` 前，M1 publisher confirm ACK，M3 listener 方法入口，M4 JSON 解析完成，M5 业务处理完成。

## A. Outbox P0→P5 拆分

| 子阶段 | 含义 | Avg (ms) | P50 | P95 | P99 | Max |
|---|---|---:|---:|---:|---:|---:|
| P1-P0 | 等待下一轮 scheduler | 1499.987 | 2143.130 | 2439.910 | 2441.740 | 2506.750 |
| P4-P3 | pending SELECT | 8.047 | 8.190 | 8.213 | 8.283 | 8.550 |
| P6-P5 | claim UPDATE | 54.729 | 45.810 | 84.850 | 209.620 | 442.040 |
| P8-P7 | publisher executor queue | 0.004 | 0.010 | 0.051 | 0.080 | 0.090 |
| P9-P8 | claim 后到 Rabbit send | 0.008 | 0.000 | 0.080 | 0.161 | 0.310 |
| P5-P0 | INSERT 到 claim 开始 | 3119.234 | 3293.400 | 5045.470 | **5229.020** | 5259.260 |

### Scheduler 证据

源码只有一个 `@Scheduled(fixedDelayString = "${tokenhub.messaging.poll-interval-ms:200}")`，项目没有自定义 `TaskScheduler`。运行时日志始终显示线程 `scheduling-1`。

本次两轮有效 publisher cycle：

| Cycle | selected/claimed | cycle duration | scheduler drift | pending query |
|---:|---:|---:|---:|---:|
| 1145 | 30 / 30 | 2511.03ms | 17.88ms | 7.68ms |
| 1146 | 70 / 70 | 3320.35ms | 56.35ms | 8.19ms |

`fixedDelay=200ms` 的实际语义是“上一轮结束后再等待 200ms”。但本轮 scheduler 的主要延迟不是 drift，而是同一个 `scheduling-1` 线程在一轮中同步执行多次 claim 和 publish；下一轮不能并行开始。日志中 `executorActive=0, executorQueue=0`，并且 P8-P7 P99 只有 0.08ms，排除了 publisher executor queue 是主因。

### Hikari 证据

Test C 期间约 150 个采样点：

| 指标 | 结果 |
|---|---:|
| maximumPoolSize | 10 |
| total connections | 稳定为 10 |
| active 峰值 | 10 |
| waiting 峰值 | 70 |
| waiting > 0 的采样点 | 10 / 150 |

当前实现没有在每个 JDBC `getConnection()` 调用处插入精确计时，因此不能把“waiting=70”直接换算成某一条消息的 connection-acquire P99。可以确认的是：HTTP 高并发期间确实出现过连接池竞争；但 pending SELECT 和 claim UPDATE 的实际 SQL 阶段没有秒级耗时，连接池竞争不是 P0→P5 的唯一或首要解释。

### Outbox SQL 证据

当前数据库上的 `EXPLAIN`：

```text
key=idx_message_status_retry
rows=2
Extra=Using index condition; Using filesort
```

`EXPLAIN ANALYZE` 在空闲状态下实际耗时约 **0.096ms**，扫描 0 行。索引确实存在 `ORDER BY id` 的 filesort，但本轮测量没有证据表明它能造成 5 秒级等待；它属于后续可优化项，不是本轮已证明的第一根因。

## B. RabbitMQ M0→M5 拆分

| 子阶段 | 含义 | Avg (ms) | P50 | P95 | P99 | Max |
|---|---|---:|---:|---:|---:|---:|
| M1-M0 | publish 到 confirm ACK | 118.276 | 108.610 | 249.530 | 293.680 | 296.790 |
| M3-M1 | confirm 到 listener 方法入口 | 1685.534 | 1951.030 | 2104.890 | **2116.850** | 2119.160 |
| M4-M3 | listener 入口到 JSON 解析完成 | 0.134 | 0.100 | 0.231 | 0.994 | 1.360 |
| M5-M4 | listener 业务处理 | 30.766 | 17.280 | 100.340 | 166.550 | 411.730 |

### RabbitMQ 管理 API 证据

运行时 `/api/consumers` 返回：

```text
queue=seckill.order.queue
consumers=1
prefetch_count=250
ack_required=true
```

`rabbit-c.csv` 的约 250 个采样点中：

- `messages_ready`：最大值 0，始终没有 broker ready backlog；
- `messages_unacknowledged`：最大值 46；
- consumer 数量：始终 1。

因此不能把 M1→M3 直接归因于 RabbitMQ broker 网络或磁盘压力。更符合观测的解释是：单 consumer 使用较大的 prefetch，把消息提前拿到未确认状态，但应用侧 listener 仍以单 consumer/串行路径逐个交付和执行。由于没有配置显式 listener executor，也没有第二个 consumer，消息会在“已被预取但 listener 尚未开始”的阶段等待。

M2“消息进入目标 queue 的精确时间”没有伪造：RabbitMQ 管理 API 能提供 ready/unacked 统计，但不能为单条消息提供可靠的入队 timestamp。因此本报告只使用 ready/unacked 时间序列作为辅助证据。

## C. 端到端对照

### 客户端观测

| 并发 | 总请求 | 成功 | 失败 | Avg | P50 | P95 | P99 | Max |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 262 | 100 | 162 | 430.821ms 接口 | 281.076ms | 1083.356ms | 1145.533ms | 1148.047ms |

客户端 E2E（只对 100 个成功订单）：平均 **5830.59ms**，P50 **6275ms**，P95 **8174ms**，P99 **8260ms**，最大 **8309ms**。

### 服务端 T0-T11

100 个成功订单的服务端 E2E：平均 **5502.754ms**，P50 **5998.225ms**，P95 **7942.635ms**，P99 **8011.560ms**，最大 **8050.003ms**。

客户端 P99 比服务端 P99 多约 248ms，仍然不是秒级主因。

## 对十个问题的明确回答

1. **Outbox 5.23 秒的最大 contributor：** scheduler 等待和同一 scheduler 线程中的串行 claim/publish；P1-P0 P99 2.44 秒，claim 后消息还会排在同一批更早消息之后。
2. **200ms 轮询为何出现秒级等待：** `fixedDelay` 不是每 200ms 强制并行启动；上一轮要先完成。当前一轮处理 30/70 条消息用了 2.51/3.32 秒，而且只有 `scheduling-1`。
3. **RabbitMQ→Consumer 2.12 秒主要发生在哪里：** M1 confirm 后到 M3 listener 入口；M4-M5 业务处理只有 166.55ms P99，因此主要是 consumer 交付/串行调度等待。
4. **RabbitMQ broker 是否真的积压：** 没有 ready 积压；ready 始终为 0。存在最高 46 条 unacked，表示消息已被唯一 consumer 预取但尚未完成确认。
5. **Consumer concurrency/prefetch 是否形成瓶颈：** 运行时确认 1 consumer、prefetch 250；单 consumer 是明确的容量约束，prefetch 使等待消息表现为 unacked 而非 ready。
6. **Hikari 是否存在 contention：** 存在，waiting 峰值 70、active 峰值 10；但 SQL 本身毫秒级，当前不能把它定为 5 秒级黑盒等待的唯一根因。
7. **Spring scheduler 是否单线程竞争：** 是。源码无自定义 scheduler，日志线程为 `scheduling-1`，publisher cycle 不能并行。
8. **Publisher executor 是否 queue/in-flight bottleneck：** 不是。当前路径没有独立 publisher executor；日志 queue=0，P8-P7 P99=0.08ms。in-flight 只在 semaphore 层面限流，本样本 rejected=0。
9. **Outbox SQL/index 是否主瓶颈：** 目前证据显示不是主瓶颈。使用 `idx_message_status_retry` 但有 filesort；EXPLAIN ANALYZE 空闲耗时约 0.096ms，属于次要优化项。
10. **当前瓶颈排序：** 第一是单线程 scheduler 串行处理 Outbox；第二是单 Rabbit consumer 的交付/执行容量；第三是高并发 HTTP 下 Hikari 连接竞争。SQL filesort 是后续项，不是已证明的秒级根因。

## 当前不确定性与边界

- 本轮没有为每次 `DataSource.getConnection()` 加代理计时，因此 Hikari 只能给出池级 contention 证据，不能给出精确的单请求 acquire latency 分布。
- RabbitMQ 没有可靠的单消息入队时间戳，M2 只能用 ready/unacked 序列辅助判断。
- 本轮没有采集宿主机 CPU、磁盘 IO、JVM GC，也没有故障注入；不能据此判断机器资源或异常恢复场景。
- Test C 接口统计中包含大量库存耗尽请求；阶段百分位只使用 100 个成功 requestId，不把失败请求混入 E2E 阶段数据。

## 本轮新增观测实现

- `src/main/java/com/tokenhub/profiling/SeckillTrace.java`：支持 P0-P9、M0-M5 时间点。
- `src/main/java/com/tokenhub/messaging/LocalMessagePublisher.java`：记录 scheduler cycle、SELECT、claim、executor/in-flight 和 drift。
- `src/main/java/com/tokenhub/messaging/SeckillOrderConsumer.java`、`SeckillOrderService.java`：记录 listener delivery、反序列化和业务完成。
- `src/main/java/com/tokenhub/profiling/InfrastructureSampler.java`：独立线程采样 Hikari，不改变 Spring scheduler。

## 本轮停止条件

根因定位已完成，本轮不实施任何优化。下一轮若要优化，应分别针对 scheduler 串行 claim、Rabbit consumer 并发/交付和连接池竞争设计变更，并重新使用同一口径验证。

## 给初学者的一句话解释

原来以为“每 200ms 扫一次库存消息”，但实际上只有一个后台线程；它一次拿到一批消息后要一条条 claim 和发送，上一批没做完下一批就不能开始，所以消息会排队几秒。RabbitMQ 这边也只有一个消费者，消息虽然已经被它提前拿走（所以 ready 是 0、unacked 却升高），但还要等这个消费者逐个交给业务代码；真正的 MySQL 下单只花了约几十到一百多毫秒，不是主要矛盾。
