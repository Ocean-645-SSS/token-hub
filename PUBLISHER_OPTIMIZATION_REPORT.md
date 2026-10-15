# Outbox Publisher 第二轮第一阶段报告

## 1. Before 架构

优化前，Spring `scheduling-1` 同时负责轮询、查询 pending、逐条 claim 和 RabbitMQ publish，并等待每条消息的 publisher confirm。虽然配置了 200ms `fixedDelay`，但上一轮未结束前下一轮不会开始。

## 2. 已确认根因

上一轮 100 并发、100 个成功订单隔离样本确认：

| 指标 | Before P99 |
|---|---:|
| Client E2E | 8260 ms |
| Server E2E | 8011.56 ms |
| Outbox INSERT → claim | 5229.02 ms |
| Scheduler wait | 2441.74 ms |
| Rabbit confirm → listener | 2116.85 ms |

主要原因是单 scheduler 串行处理 Publisher；RabbitMQ 侧的 1 consumer / prefetch=250 是下游独立容量约束，本阶段没有修改。

## 3. Publisher 修改内容

- 新增专用 `ThreadPoolTaskExecutor`，默认 `core=4`、`max=8`、`queue=500`。
- `MessagingProperties` 暴露 `publisherCoreThreads`、`publisherMaxThreads`、`publisherQueueCapacity`，均可通过环境变量覆盖。
- scheduler 只负责 stale claim recovery、按容量查询、claim 和 dispatch，不等待 RabbitMQ confirm。
- worker 负责 `RabbitTemplate.convertAndSend`、异步 confirm、ACK 状态更新、Redis reservation 条件更新和 retry。
- 保留 `PENDING → SENDING → SENT` 的 seckill/order claim 流程及 stale `SENDING` recovery。
- 保留 `stock.release` 的现有特殊状态语义：发送动作移入 worker，但不在 broker confirm 前替消费者把 outbox 标为 SENT，因为消费者以 status=0 的条件更新保证库存释放幂等。

## 4. 修改后的并发模型

```text
Spring scheduler
  └─ recover / SELECT / capacity-limited claim / dispatch
       ├─ publisher-1: Rabbit send + async confirm
       ├─ publisher-2: Rabbit send + async confirm
       └─ publisher-...: Rabbit send + async confirm
```

每轮 claim 上限取 `batchSize`、in-flight permits 和 executor 当前可接收容量的最小值。executor 使用 `AbortPolicy`；提交竞态导致拒绝时，已 claim 消息立即写回原有 retry 状态并释放 permit，记录 WARN 和 rejected counter，不静默丢弃。

## 5. 可靠性机制是否保持

- RabbitMQ correlated publisher confirm：保持，未关闭。
- `SENDING` claim：保持。
- NACK / send exception retry：保持。
- stale `SENDING` recovery：保持原 mapper SQL。
- requestId / consumer 幂等：未修改。
- Redis reservation 状态：仍在 confirm ACK 后通过 Lua 条件更新为 SENT。
- 原始 RabbitTemplate / ConnectionFactory 框架管理方式：保持，未创建共享 raw Channel。

## 6. 测试环境与口径

代码验证环境为 JDK 17、Spring Boot 3.3.13。After 使用临时 MySQL、现有 RabbitMQ/Redis、当前应用 jar，开启 `tokenhub.profiling.enabled=true`；Consumer、prefetch、Hikari、MySQL 索引和 benchmark 并发模型均未改动。

正式样本使用 activity 4、100 并发、5 秒、100 个库存，最终接受 100 个订单；开始前完成 10 并发 warm-up，这是与 Before 相同的 100 并发 / 100 成功订单隔离口径。客户端原始结果保存在 `docs/performance/2026-09-21-publisher-optimization/formal-profiled-interface.csv` 和 `formal-profiled-accepted.csv.e2e.csv`，服务端 trace 与 Publisher profile 保存在 `app.stdout.log`。

百分位使用 warm-up 后正式样本的 100 条成功请求；Publisher cycle 使用正式样本对应的非空 cycle。之后另做了 activity 5 的 p1 instrumentation spot sample，确认 `P1-P0` P99 为 1194.839ms；该 spot sample 未重复 warm-up，因此不混入主表的 Before/After 百分比。

## 7. Before / After 数据

| Metric | Before | After | Change |
|---|---:|---:|---:|
| Client E2E P99 | 8260 ms | 5343 ms | -35.31% |
| Server E2E P99 | 8011.56 ms | 4544.578 ms | -43.27% |
| Outbox INSERT → claim P99 | 5229.02 ms | 1316.377 ms | -74.83% |
| Scheduler wait P99 | 2441.74 ms | 见补充样本 | — |
| Publisher cycle duration | 2511–3320 ms | 1132.981 ms P99 | 下降 |
| Rabbit confirm → listener P99 | 2116.85 ms | 3076.006 ms | +45.31% |

## 8. Publisher 自身改善

正式样本中 `selected=100 / claimed=100 / dispatched=100 / confirmed=100 / rejected=0`，最终 `pending=0`。Publisher cycle P99 为 1132.981ms；正式样本结束时队列回到 0、active 回到 0，说明发送已从 scheduler 线程移交给 `publisher-*` worker。这个结果支持 Publisher 等待显著下降，但不代表全链路都下降。

## 9. E2E 改善

同口径 After 样本的 Client E2E P99 从 8260ms 降至 5343ms，Server E2E P99 从 8011.56ms 降至 4544.578ms；分别下降 35.31% 和 43.27%。

## 10. 是否发生 bottleneck shift

已观察到 bottleneck shift：Outbox INSERT→claim P99 下降 74.83%，但 Rabbit confirm→listener P99 上升 45.31%。这与下游固定 1 consumer / prefetch=250 的容量约束一致，不能把后者上升判定为 Publisher 优化失败。

## 11. RabbitMQ / Consumer 当前表现

本阶段未修改 consumer concurrency、prefetch、RabbitListener 配置或 Consumer 业务代码。After 结束时 `seckill.order.queue` 为 `ready=0, unacked=0, consumers=1`；其它现有队列也为 ready/unacked 0，consumer 数量保持 1。

## 12. 一致性验证

代码级测试结果：

- 7 个 Publisher/config focused tests 通过。
- ACK 只确认已 claim 消息。
- NACK 进入 retry。
- claim 丢失时不发送。
- executor 无容量时不继续 claim。
- worker 发送线程为 `publisher-*`，不为 scheduler 线程。
- 全量 27 个测试通过，`mvn clean package -DskipTests` 通过。

After activity 4：MySQL 订单数 100、distinct user 100、distinct requestId 100、重复 requestId 0；Redis `seckill:stock:4=0`、用户集合 cardinality 100；`local_message` 全部为 SENT（status=1），没有残留 PENDING/SENDING/FAILED/retry；Rabbit ready/unacked 均为 0。

## 13. 当前限制

- 已运行真实 RabbitMQ publisher confirm 链路；正式样本确认 100 条消息。
- 没有执行故障注入、JVM 中止或队列拒绝的真实部署验证；拒绝恢复由单元测试和代码路径覆盖。
- p1 instrumentation spot sample 的 `P1-P0` 少量负值表示消息在当前 scheduler cycle 已开始后才完成 outbox INSERT，属于同一轮被观察到的正常边界；主表不将该 spot sample 与 warm-up 后正式样本混算。
- `stock.release` 保留了原有消费者条件更新所要求的非 SENDING 状态语义，这是兼容性约束，不是本阶段新增的业务变化。

## 14. 下一步建议

下一阶段如继续优化，应单独处理 confirm→listener 暴露出的 Consumer 容量约束；本阶段不应回头修改 Consumer、prefetch、Hikari、MySQL 索引或其它基础设施参数。

## 结论

原来一个 scheduler 线程既扫描 outbox 又逐条发送，导致一轮任务需要数秒；现在 scheduler 只负责按承载能力 claim 和分发，真正发送由有界 Publisher worker pool 并发执行。本轮实测确认 Outbox 和 E2E 延迟下降，同时暴露下游单 consumer 的新瓶颈；一致性检查通过，下一阶段边界明确为 Consumer 链路。
