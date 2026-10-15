# RabbitMQ Consumer 第二轮第二阶段报告

## 1. Before Consumer 架构

Publisher 优化完成后，`seckill.order.queue` 使用 Spring AMQP 默认 Simple listener container：1 个 Consumer、prefetch=250、AUTO ACK。大量消息被单个 Consumer 预取后，在 listener 实际处理前等待。

本阶段只调整 seckill listener 的消费能力，Publisher、Outbox、Redis、MySQL schema、Hikari、HTTP 和 benchmark 均未修改。

## 2. 当前根因

Publisher 阶段已将 Rabbit confirm 前的等待显著压低，但 Before 的 `Rabbit confirm → listener` P99 仍为 3076.006ms。上一轮 Rabbit 采样中 `messages_ready` 基本不积压、`unacked` 增长，符合单 Consumer 预取后串行交付的特征。

## 3. Consumer 并发安全检查

- `requestId` 继续使用 `orders.uk_request_id` 唯一约束。
- 同一用户同一活动继续使用 `orders.uk_user_activity` 唯一约束。
- `SeckillOrderService.create()` 的 `@Transactional` 未修改，不扩大事务范围、不删除事务。
- Redis reservation 仍只接受 `PENDING/SENT`，并继续使用现有条件状态更新。
- listener 仍使用 Spring AMQP AUTO ACK：方法正常返回后确认，异常继续交给原有容器错误处理，不提前 ACK、不吞异常。
- `ObjectMapper`、MyBatis mapper、`StringRedisTemplate` 和 `SeckillTrace` 的共享状态可并发使用；`SeckillTrace` 使用 `ConcurrentHashMap`，每个 requestId 独立记录。
- 不存在必须按用户或活动全局串行的业务逻辑；不同 requestId / userId 的订单允许并行创建，唯一约束负责最后的重复保护。

## 4. 修改内容

新增 `RabbitConsumerProperties` 和专用 `seckillRabbitListenerContainerFactory`：

```text
concurrency     = 4
maxConcurrency  = 8
prefetch        = 32
ack mode        = 保持 Spring Boot 原有 AUTO ACK
```

只有 `SeckillOrderConsumer` 绑定该 factory。其它队列没有切换到该 factory，Publisher 线程池和 dispatch 逻辑保持不变。

## 5. Consumer 配置 Before / After

| 配置 | Before | After |
|---|---:|---:|
| Initial consumer concurrency | 1 | 4 |
| Max concurrency | 未显式配置 | 8 |
| Prefetch | 250 | 32 |
| Formal sample observed consumers | 1 | 5–6，排空后回到 4 |
| ACK | AUTO | AUTO |

## 6. 测试口径

After 使用与 Publisher After 相同的流程：10 并发 warm-up，确认 outbox 与 RabbitMQ 初始为空，再执行 100 并发、100 库存、5 秒正式样本；benchmark 客户端和请求模型未改动。

原始数据：

- [formal-interface.csv](<E:/CS/project/token-hub/docs/performance/2026-09-21-consumer-optimization/formal-interface.csv>)
- [formal-accepted.csv.e2e.csv](<E:/CS/project/token-hub/docs/performance/2026-09-21-consumer-optimization/formal-accepted.csv.e2e.csv>)
- [app.stdout.log](<E:/CS/project/token-hub/docs/performance/2026-09-21-consumer-optimization/app.stdout.log>)

## 7. Before / After 延迟

| Metric | Before | After | Change |
|---|---:|---:|---:|
| Client E2E P99 | 5343 ms | 5361 ms | +0.34% |
| Server E2E P99 | 4544.578 ms | 1747.713 ms | -61.54% |
| Outbox INSERT → claim P99 | 1316.377 ms | 1181.071 ms | -10.28% |
| Rabbit confirm → listener P99 | 3076.006 ms | 360.828 ms | -88.27% |
| Rabbit publish → confirm P99 | 293.680 ms | 138.368 ms | -52.88% |
| MySQL order transaction M4 → M5 P99 | 25.069 ms | 45.604 ms | +81.91% |

服务端 E2E 和核心 `confirm → listener` 阶段明显下降；Client E2E 基本不变，说明客户端完成结果观察或其它 HTTP 侧等待已成为端到端剩余成本，本阶段不继续修改这些组件。

## 8. RabbitMQ ready / unacked 时间序列

采样间隔约 100ms，正式样本共 173 个有效样本：

| 指标 | Formal After |
|---|---:|
| `messages_ready` 峰值 | 0 |
| `messages_unacknowledged` 峰值 | 0 |
| observed consumers | 5–6 |
| publish rate 峰值 | 20 msg/s |
| deliver rate 峰值 | 20 msg/s |
| ack rate 峰值 | 20 msg/s |

warm-up 期间 observed consumers 为 4–5，`unacked` 峰值 31；正式样本没有形成 ready 堆积。原始时间序列保存在 [rabbit-formal.csv](<E:/CS/project/token-hub/docs/performance/2026-09-21-consumer-optimization/rabbit-formal.csv>) 和 [rabbit-warmup.csv](<E:/CS/project/token-hub/docs/performance/2026-09-21-consumer-optimization/rabbit-warmup.csv>)。

## 9. Confirm → listener 改善

`M1 → M3` P99 从 3076.006ms 降至 360.828ms，下降 88.27%；平均值从 1769.652ms 降至 135.393ms。`M3 → M4` JSON parse P99 为 0.121ms，`M4 → M5` 业务/订单事务 P99 为 45.604ms。

这说明本阶段确实解决了单 Consumer 预取后串行等待的问题，而不是只改变了 RabbitMQ ready 队列表现。

## 10. 新的 bottleneck shift

Consumer 并发提升后，`M1 → M3` 不再是主要服务端瓶颈；Hikari 采样显示正式压测窗口内：

- maximumPoolSize 仍为 10；
- active 峰值 10；
- waiting 峰值 56；
- 90 个采样点中仅 1 个采样点 `waiting > 0`。

同时 MySQL order transaction P99 从 25.069ms 上升到 45.604ms。该结果记录为新的数据库连接/事务竞争信号，本阶段不扩大 Hikari、不改 MySQL 索引、不继续优化第三个组件。

## 11. 一致性验证

activity 3 正式样本结果：

- final orders = 100；
- distinct users = 100；
- distinct requestId = 100；
- duplicate requestId = 0；
- duplicate user/activity order = 0；
- Redis `seckill:stock:3` = 0；用户集合 cardinality = 100；
- 100 个正式 requestId 的 reservation 均为 `ORDER_CREATED`，missing final order = 0；
- `local_message` status=1（SENT）共 1566 条，无 PENDING/SENDING/FAILED；
- Publisher final stats：published=1566、confirmed=1566、failed=0、retries=0、rejected=0；
- RabbitMQ 排空后 `seckill.order.queue ready=0 / unacked=0 / consumers=4`。

没有发现数据库唯一键冲突导致的重复订单、重复 requestId 或状态竞争。

## 12. 验证结果

- focused Consumer/config/service tests：4/4 通过；
- full `mvn test`：30/30 通过；
- `mvn clean package -DskipTests`：成功；
- Consumer runtime formal：100/100 accepted and completed；
- RabbitMQ confirm 链路真实运行，100 条正式消息均完成。

## 13. 当前限制

- `messages_unacknowledged` 的 RabbitMQ Management API formal 样本峰值为 0，而 warm-up 峰值为 31；报告保留原始时间序列，不用单点采样推断更细的 broker 内部行为。
- 没有执行 JVM 中止、Consumer 进程崩溃或异常重投递故障注入。
- 没有修改 Hikari、MySQL、Publisher、Redis、Tomcat 或 JVM 参数，因此无法把新的事务等待进一步归因到某个基础设施子项。

## 14. 下一步建议

本阶段到此停止。若进入下一阶段，应单独研究 Hikari/数据库事务竞争或客户端结果观察等待，并重新设计独立单变量实验；本报告不在本阶段顺手修改这些组件。

## 结论

原来 RabbitMQ 只有一个 Consumer，并且 prefetch=250，大量消息被一个 Consumer 提前拿走后排队。优化后通过受控增加 Consumer 并发并将 prefetch 调整为 32，让消息能够并行消费。`confirm→listener` P99 从 3076.006ms 降至 360.828ms，Server E2E 从 4544.578ms 降至 1747.713ms。当前新的主要瓶颈表现为 Hikari/数据库事务竞争信号以及客户端端到端结果观察等待；按阶段边界停止，不继续修改第三个组件。
