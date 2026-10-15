# Publisher Executor Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Outbox Publisher 的扫描、claim、dispatch 与 RabbitMQ publish 解耦为 scheduler + 有界 Publisher worker pool，并保持 confirm、重试、stale claim recovery、Redis reservation 和幂等语义不变。

**Architecture:** `LocalMessagePublisher.publishPending()` 只恢复 stale claim、查询当前可承载数量的 pending 消息、claim 并提交到专用 `ThreadPoolTaskExecutor`；worker 执行 `RabbitTemplate.convertAndSend`，由 correlated publisher confirm callback 完成 SENT/重试和 permit 释放。Executor 使用 `core=4`、`max=8`、`queue=500` 的显式配置，拒绝使用 `AbortPolicy`，被拒绝的已 claim 消息立即转入原有 retry 状态，避免静默丢失。

**Tech Stack:** Java 17, Spring Boot 3.3.13, Spring AMQP correlated publisher confirms, MyBatis annotations, Redis Lua, JUnit 5, Mockito.

## Global Constraints

- 本阶段只允许优化 Outbox Publisher。
- 不修改 RabbitMQ consumer concurrency、prefetch、RabbitListener 配置、Hikari maximumPoolSize、MySQL 索引、Redis、秒杀 Lua、HTTP/Tomcat 参数、benchmark 并发模型和 Consumer 业务代码。
- 保留 Transactional Outbox、SENDING claim、RabbitMQ publisher confirm、retry、stale claim recovery、requestId 幂等、Consumer 幂等和 Redis reservation 状态一致性。
- 不使用 `newCachedThreadPool`、无限队列、`SimpleAsyncTaskExecutor` 或无界并发。
- claim 数量不得超过当前 in-flight permit 与 executor 可接收容量；executor rejection 必须可观测且可恢复。
- 继续使用 `${spring.rabbitmq.publisher-confirm-type: correlated}`，不得关闭 publisher confirm。

## File Map

- Modify: `src/main/java/com/tokenhub/config/MessagingProperties.java` — 增加 publisher core/max/queue 三个有校验的配置字段。
- Modify: `src/main/resources/application.yaml` — 只增加 Publisher executor 的环境可覆盖配置。
- Create: `src/main/java/com/tokenhub/config/PublisherExecutorConfig.java` — 创建显式、有界、带 `AbortPolicy` 的专用 executor bean。
- Modify: `src/main/java/com/tokenhub/messaging/LocalMessagePublisher.java` — scheduler 只做 poll/claim/dispatch，worker 负责 send/confirm；补充 executor profile 和拒绝恢复。
- Modify: `src/test/java/com/tokenhub/messaging/LocalMessagePublisherTest.java` — 覆盖 executor dispatch、claim 后 rejection retry、confirm ACK/NACK、容量限制和未 claim 不发送。
- Create: `src/test/java/com/tokenhub/config/MessagingPropertiesTest.java` — 覆盖新增配置默认构造和非法值校验。
- Create: `PUBLISHER_OPTIMIZATION_REPORT.md` — 记录实现边界、配置、验证结果；压测数据未实际采集前不得伪造 After 数值。

### Task 1: Add bounded Publisher executor configuration

**Files:**
- Modify: `src/main/java/com/tokenhub/config/MessagingProperties.java`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/java/com/tokenhub/config/PublisherExecutorConfig.java`
- Test: `src/test/java/com/tokenhub/config/MessagingPropertiesTest.java`

**Interfaces:**
- `MessagingProperties` exposes `publisherCoreThreads()`, `publisherMaxThreads()`, and `publisherQueueCapacity()`.
- Spring registers a `ThreadPoolTaskExecutor` bean named `publisherExecutor` with those values and `ThreadPoolExecutor.AbortPolicy`.

- [ ] **Step 1: Write configuration validation tests**

```java
@Test void acceptsBoundedPublisherExecutorConfiguration() {
    MessagingProperties properties = new MessagingProperties(200, 500, 1000, 30, 5, 4, 8, 500);
    assertEquals(4, properties.publisherCoreThreads());
    assertEquals(8, properties.publisherMaxThreads());
    assertEquals(500, properties.publisherQueueCapacity());
}

@Test void rejectsInvalidPublisherExecutorConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> new MessagingProperties(200, 500, 1000, 30, 5, 0, 8, 500));
    assertThrows(IllegalArgumentException.class, () -> new MessagingProperties(200, 500, 1000, 30, 5, 8, 4, 500));
    assertThrows(IllegalArgumentException.class, () -> new MessagingProperties(200, 500, 1000, 30, 5, 4, 8, 0));
}
```

- [ ] **Step 2: Run the focused test and verify it fails because the new constructor/accessors do not exist**

Run: `mvn -Dtest=MessagingPropertiesTest test`

Expected: FAIL at compilation against the current five-argument record.

- [ ] **Step 3: Add validated properties and executor bean**

`MessagingProperties` must keep the existing five fields first and append `publisherCoreThreads`, `publisherMaxThreads`, and `publisherQueueCapacity`; reject non-positive values and `publisherCoreThreads > publisherMaxThreads`.

`PublisherExecutorConfig` must configure:

```java
executor.setCorePoolSize(properties.publisherCoreThreads());
executor.setMaxPoolSize(properties.publisherMaxThreads());
executor.setQueueCapacity(properties.publisherQueueCapacity());
executor.setThreadNamePrefix("publisher-");
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
executor.setWaitForTasksToCompleteOnShutdown(true);
executor.setAwaitTerminationSeconds(properties.claimTimeoutSeconds());
executor.initialize();
```

- [ ] **Step 4: Add environment-overridable YAML values**

```yaml
publisher-core-threads: ${TOKENHUB_PUBLISHER_CORE_THREADS:4}
publisher-max-threads: ${TOKENHUB_PUBLISHER_MAX_THREADS:8}
publisher-queue-capacity: ${TOKENHUB_PUBLISHER_QUEUE_CAPACITY:500}
```

- [ ] **Step 5: Run configuration tests**

Run: `mvn -Dtest=MessagingPropertiesTest test`

Expected: PASS.

### Task 2: Move publish execution off the scheduler

**Files:**
- Modify: `src/main/java/com/tokenhub/messaging/LocalMessagePublisher.java`
- Modify: `src/test/java/com/tokenhub/messaging/LocalMessagePublisherTest.java`

**Interfaces:**
- Constructor adds `ThreadPoolTaskExecutor publisherExecutor`.
- `publishPending()` never calls `RabbitTemplate.convertAndSend` directly.
- `dispatchClaimed(LocalMessage)` submits one worker task and on `TaskRejectedException` / `RejectedExecutionException` calls `recordClaimedRetry` and releases the permit.

- [ ] **Step 1: Add tests that assert scheduler dispatches asynchronously and rejected tasks are retried**

Use a real `ThreadPoolTaskExecutor` in tests with `core=1`, `max=1`, `queue=0`, and a `CountDownLatch` around `RabbitTemplate.convertAndSend`; verify `publishPending()` returns before the blocked worker is released. For rejection, use a saturated executor and verify `recordClaimedRetry(id, 5)` is called and no Rabbit send occurs for the rejected message.

- [ ] **Step 2: Run focused publisher tests and verify they fail**

Run: `mvn -Dtest=LocalMessagePublisherTest test`

Expected: FAIL because the publisher has no executor constructor or dispatch path.

- [ ] **Step 3: Inject the executor and separate scheduler from worker**

Implement the scheduler flow in this order:

```java
recoverExpiredClaims();
int capacity = dispatchCapacity();
List<LocalMessage> pending = messages.findPending(Math.min(properties.batchSize(), capacity));
for (LocalMessage message : pending) {
    if (!inFlight.tryAcquire()) break;
    if (messages.claim(message.getId()) != 1) {
        inFlight.release();
        continue;
    }
    try {
        publisherExecutor.execute(() -> publishClaimed(message));
    } catch (RejectedExecutionException exception) {
        recordClaimedRetryAndRelease(message, exception);
    }
}
```

`publishClaimed` keeps the existing `RabbitTemplate`, correlated `CorrelationData`, confirm callback, ACK transition, Redis conditional reservation update, NACK retry, synchronous send exception retry, and final permit release. `stock.release` must use the same claim/dispatch/retry boundary and must not bypass the capacity gate.

- [ ] **Step 4: Implement dispatch capacity and rejection observability**

Compute the per-cycle claim limit from the minimum of `batchSize`, available in-flight permits, and executor acceptance capacity. Executor acceptance capacity is `queue.remainingCapacity() + max(0, maxPoolSize - poolSize)` from the underlying `ThreadPoolExecutor`; submit-time rejection remains handled as a race-safe fallback.

Record `publisher executor rejected` at WARN with message id and increment a `LongAdder rejected`; include core/max/active/pool/queue/remaining/completed/rejected in the profile log. No rejection policy may silently discard a task.

- [ ] **Step 5: Update tests for the new constructor and run focused tests**

Run: `mvn -Dtest=LocalMessagePublisherTest test`

Expected: PASS; ACK marks SENT, NACK schedules retry, a lost claim never sends, and rejection schedules retry.

### Task 3: Preserve and expose publisher profiling

**Files:**
- Modify: `src/main/java/com/tokenhub/messaging/LocalMessagePublisher.java`
- Modify: `src/test/java/com/tokenhub/messaging/LocalMessagePublisherTest.java`

**Interfaces:**
- Profile output includes scheduler cycle start/duration/drift, selected/claimed/dispatched, executor queue wait, queue depth, active workers, and rejected count.
- Per-message trace records `claim -> worker start`, `worker start -> Rabbit send`, and existing `Rabbit send -> confirm` timestamps without changing consumer code.

- [ ] **Step 1: Add a test-visible worker-start/send ordering assertion**

Capture `System.nanoTime()` at claim dispatch, at the executor task start, and immediately before `convertAndSend`; assert the send is performed by a `publisher-` thread and not by `scheduling-1`.

- [ ] **Step 2: Add timing and executor gauges to existing logs**

Keep `SeckillTrace` marker names compatible with existing T5/T6/T7 interpretation and add distinct publisher markers for worker start and send. Log executor values from `ThreadPoolExecutor` after the cycle; do not alter Rabbit consumer profiling.

- [ ] **Step 3: Run focused tests and compile the application**

Run: `mvn -Dtest=LocalMessagePublisherTest,MessagingPropertiesTest test`

Expected: PASS.

### Task 4: Run repository verification and write the report

**Files:**
- Create: `PUBLISHER_OPTIMIZATION_REPORT.md`

- [ ] **Step 1: Verify the restricted diff**

Run: `git diff -- src/main/java/com/tokenhub/messaging src/main/java/com/tokenhub/config src/main/resources/application.yaml src/test/java/com/tokenhub/messaging src/test/java/com/tokenhub/config`

Expected: only Publisher executor/configuration/profiling and its tests are changed; no consumer, prefetch, Hikari, MySQL schema/index, Redis Lua, HTTP/Tomcat or benchmark changes appear.

- [ ] **Step 2: Run the full test suite**

Run: `mvn test`

Expected: PASS.

- [ ] **Step 3: Build the application**

Run: `mvn clean package -DskipTests`

Expected: BUILD SUCCESS.

- [ ] **Step 4: Write the report with measured values only**

The report must include Before architecture, confirmed root cause, exact final executor configuration, dispatch model, reliability mechanisms, test environment, Before/After table, publisher improvement, E2E improvement, bottleneck shift, unchanged consumer behavior, consistency checks, limitations, and next steps. If this session cannot run the same 100-success-order benchmark, mark After fields as “未执行/待补测” rather than inventing values.

## Self-Review Checklist

- [ ] Scheduler never waits for publisher confirm.
- [ ] Only claimed rows are sent; claim failure does not send.
- [ ] Queue rejection is logged, counted, and transitions claimed rows to retryable state.
- [ ] SENDING rows recover through existing stale-claim SQL.
- [ ] Confirm ACK is still required before SENT and Redis reservation SENT update.
- [ ] RabbitTemplate/ConnectionFactory is used through Spring; no shared raw Channel is created.
- [ ] Consumer concurrency and prefetch remain unchanged.
- [ ] No benchmark or non-Publisher infrastructure tuning was introduced.
