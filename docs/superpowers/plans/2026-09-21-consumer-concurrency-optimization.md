# RabbitMQ Consumer 第二阶段优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在不改变 Publisher、Outbox、数据库、Redis、HTTP 和 benchmark 的前提下，提升 `seckill.order.queue` 的 RabbitMQ Consumer 并发能力，并用同口径压测验证 `confirm → listener` 延迟变化。

**Architecture:** 新增独立的 Rabbit Consumer 配置属性和专用 listener container factory，仅让 `SeckillOrderConsumer` 使用受控的 `concurrency=4`、`maxConcurrency=8`、`prefetch=32`。保留 Spring AMQP 默认 AUTO ACK、现有异常处理和 `SeckillOrderService` 事务；其它队列继续使用默认 listener factory，避免扩大变量范围。

**Tech Stack:** Spring Boot 3.3.13、Spring AMQP、RabbitMQ 3.13、MySQL 8、Redis 7、JDK 17、现有 Java benchmark 和 `SeckillTrace` profiling。

## Global Constraints

- 本阶段只允许优化 RabbitMQ Consumer 消费能力。
- 不修改 Publisher 架构和线程池、Outbox polling/batch、Hikari maximumPoolSize、MySQL 索引、Redis、秒杀 Lua、Tomcat、benchmark 客户端、HTTP 接口和订单业务逻辑。
- 保持 requestId 唯一、用户+activity 唯一约束、Redis reservation 条件更新、事务和 AUTO ACK 语义。
- Before 固定为 Publisher 优化后的数据：Client E2E P99 5343ms、Server E2E P99 4544.578ms、Outbox P0→P5 P99 1316.377ms、confirm→listener P99 3076.006ms。
- After 使用相同的 100 并发 / 100 成功订单、warm-up 和消息清空流程；只记录新增瓶颈，不继续修改第三个组件。

---

### Task 1: 固化 Consumer 配置和并发安全边界

**Files:**
- Create: `src/main/java/com/tokenhub/config/RabbitConsumerProperties.java`
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/com/tokenhub/config/RabbitConsumerPropertiesTest.java`

**Interfaces:**
- Produces `RabbitConsumerProperties(int concurrency, int maxConcurrency, int prefetch)` bound from `tokenhub.rabbit.consumer`.
- Default values are `4`, `8`, and `32`; environment overrides are `TOKENHUB_RABBIT_CONSUMER_CONCURRENCY`, `TOKENHUB_RABBIT_CONSUMER_MAX_CONCURRENCY`, and `TOKENHUB_RABBIT_CONSUMER_PREFETCH`.

- [ ] **Step 1: Write validation tests**

```java
@Test void acceptsConservativeConsumerDefaults() {
    var value = new RabbitConsumerProperties(4, 8, 32);
    assertEquals(4, value.concurrency());
    assertEquals(8, value.maxConcurrency());
    assertEquals(32, value.prefetch());
}

@Test void rejectsInvalidCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new RabbitConsumerProperties(0, 8, 32));
    assertThrows(IllegalArgumentException.class, () -> new RabbitConsumerProperties(8, 4, 32));
    assertThrows(IllegalArgumentException.class, () -> new RabbitConsumerProperties(4, 8, 0));
}
```

- [ ] **Step 2: Run the focused test and verify it fails because the properties type is absent**

Run: `mvn "-Dtest=RabbitConsumerPropertiesTest" test`

Expected: compilation failure until `RabbitConsumerProperties` is created.

- [ ] **Step 3: Implement the immutable configuration record**

```java
@ConfigurationProperties(prefix = "tokenhub.rabbit.consumer")
public record RabbitConsumerProperties(int concurrency, int maxConcurrency, int prefetch) {
    public RabbitConsumerProperties {
        if (concurrency < 1 || maxConcurrency < concurrency || prefetch < 1) {
            throw new IllegalArgumentException("tokenhub.rabbit.consumer values are invalid");
        }
    }
}
```

- [ ] **Step 4: Add environment-overridable YAML defaults**

```yaml
  rabbit:
    consumer:
      concurrency: ${TOKENHUB_RABBIT_CONSUMER_CONCURRENCY:4}
      max-concurrency: ${TOKENHUB_RABBIT_CONSUMER_MAX_CONCURRENCY:8}
      prefetch: ${TOKENHUB_RABBIT_CONSUMER_PREFETCH:32}
```

- [ ] **Step 5: Register the property in `TokenHubApplication` and rerun the focused test**

Run: `mvn "-Dtest=RabbitConsumerPropertiesTest" test`

Expected: PASS.

### Task 2: Add a dedicated seckill listener container factory

**Files:**
- Create: `src/main/java/com/tokenhub/config/RabbitConsumerConfig.java`
- Modify: `src/main/java/com/tokenhub/TokenHubApplication.java`
- Modify: `src/main/java/com/tokenhub/messaging/SeckillOrderConsumer.java`
- Test: `src/test/java/com/tokenhub/config/RabbitConsumerConfigTest.java`

**Interfaces:**
- Produces bean `seckillRabbitListenerContainerFactory` of type `SimpleRabbitListenerContainerFactory`.
- Factory uses the Spring Boot configurer for existing connection/ack defaults, then sets only `concurrentConsumers`, `maxConcurrentConsumers`, and `prefetchCount` from `RabbitConsumerProperties`.
- `SeckillOrderConsumer.consume` uses `containerFactory = "seckillRabbitListenerContainerFactory"`; `SeckillOrderService` remains unchanged.

- [ ] **Step 1: Add a factory configuration test that verifies the three capacity values**

```java
@Test void appliesConsumerCapacityWithoutChangingAckDefaults() {
    var properties = new RabbitConsumerProperties(4, 8, 32);
    var factory = new RabbitConsumerConfig().seckillRabbitListenerContainerFactory(
            new SimpleRabbitListenerContainerFactoryConfigurer(), mock(ConnectionFactory.class), properties);
    assertEquals(4, factory.getConcurrentConsumers());
    assertEquals(8, factory.getMaxConcurrentConsumers());
    assertEquals(32, factory.getPrefetchCount());
}
```

If the Spring Boot configurer requires runtime-only collaborators, keep this test at the bean-property level and verify the application context with the focused Spring test instead; do not add a custom executor or alter acknowledge mode to make the test pass.

- [ ] **Step 2: Implement the dedicated factory**

```java
@Bean
SimpleRabbitListenerContainerFactory seckillRabbitListenerContainerFactory(
        SimpleRabbitListenerContainerFactoryConfigurer configurer,
        ConnectionFactory connectionFactory,
        RabbitConsumerProperties properties) {
    var factory = new SimpleRabbitListenerContainerFactory();
    configurer.configure(factory, connectionFactory);
    factory.setConcurrentConsumers(properties.concurrency());
    factory.setMaxConcurrentConsumers(properties.maxConcurrency());
    factory.setPrefetchCount(properties.prefetch());
    return factory;
}
```

- [ ] **Step 3: Bind only `SeckillOrderConsumer` to the dedicated factory**

Keep the listener method body, exception declaration, `SeckillTrace` markers, and `SeckillOrderService` call unchanged:

```java
@RabbitListener(
        queues = RabbitMqConfig.SECKILL_QUEUE,
        containerFactory = "seckillRabbitListenerContainerFactory")
```

- [ ] **Step 4: Run focused config and consumer tests**

Run: `mvn "-Dtest=RabbitConsumerPropertiesTest,RabbitConsumerConfigTest,SeckillOrderServiceTest" test`

Expected: PASS; no Publisher test or Publisher source should change in this task.

### Task 3: Run the single-variable runtime benchmark and collect RabbitMQ evidence

**Files:**
- Create: `docs/performance/2026-09-21-consumer-optimization/` runtime CSV/log artifacts.
- Do not modify: benchmark Java source, Publisher source, Outbox configuration, database schema, Redis scripts, or HTTP handlers.

**Interfaces:**
- Uses the existing benchmark invocation and `SeckillTrace` output.
- Produces client summary, accepted-request E2E summary, application trace log, Hikari profile log, and approximately 100ms RabbitMQ samples containing ready, unacked, consumers, publish, deliver, and ack rates.

- [ ] **Step 1: Clean build and start the same application topology**

Run: `mvn clean package -DskipTests`; start the jar with the existing temporary MySQL plus the pre-existing RabbitMQ, Redis, and Elasticsearch endpoints; enable `-Dtokenhub.profiling.enabled=true`.

Expected: application starts successfully and logs the configured consumer values.

- [ ] **Step 2: Verify pre-test state**

Check:

```text
outbox pending=0
RabbitMQ ready=0 / unacked=0
seckill.order.queue consumers=1 before the new listener factory is loaded, then 4 or the configured dynamic value after startup
```

- [ ] **Step 3: Execute the unchanged warm-up and formal benchmark**

Use the existing compiled benchmark with 10-concurrency warm-up followed by 100-concurrency / 100-stock formal traffic. Do not alter request duration, concurrency, request IDs, or completion polling.

- [ ] **Step 4: Sample RabbitMQ during both runs**

Every approximately 100ms record `messages_ready`, `messages_unacknowledged`, consumer count, publish rate, deliver rate, and ack rate for `seckill.order.queue`. Keep the raw time series, not only peak values.

- [ ] **Step 5: Wait for drain and collect consistency evidence**

Record final orders, distinct users, distinct request IDs, duplicate request IDs, duplicate user/activity rows, Redis stock, missing reservations, outbox statuses/retries, and final RabbitMQ ready/unacked.

### Task 4: Analyze, report, and finish at the Consumer boundary

**Files:**
- Create: `CONSUMER_OPTIMIZATION_REPORT.md`
- Create: `docs/performance/2026-09-21-consumer-optimization/` analysis artifacts if needed.

**Interfaces:**
- Report compares the fixed Publisher-After Before values against the Consumer-After sample.
- Report explicitly records `1 / 250` versus actual consumer count and prefetch, RabbitMQ time series, `M1→M3`, `M3→M4`, `M4→M5`, Server E2E, Client E2E, Hikari observations, and consistency checks.

- [ ] **Step 1: Compute the same percentile convention used by `PUBLISHER_OPTIMIZATION_REPORT.md`**

For the 100 accepted formal requests, calculate P50/P95/P99/max for Client E2E and server trace stages. Compare `M1→M3` against the fixed 3076.006ms Before value.

- [ ] **Step 2: Classify any bottleneck shift without changing another component**

If `M1→M3` falls and Hikari/SQL/Publisher rises, report the new bottleneck only. Do not modify Hikari, MySQL, Publisher, Redis, Tomcat, or JVM settings.

- [ ] **Step 3: Write the report with real values and limitations**

The conclusion must state the actual consumer count, actual prefetch, `confirm→listener` Before/After, Server/Client E2E Before/After, consistency status, and the next boundary.

- [ ] **Step 4: Run final verification and clean temporary runtime resources**

Run: `mvn test`, `mvn clean package -DskipTests`, and `git diff --check`. Stop only the application process and temporary MySQL created for this benchmark; do not stop or remove pre-existing RabbitMQ/Redis/Elasticsearch containers.

## Self-review checklist

- Consumer safety: requestId unique, user/activity unique, Redis reservation condition updates and service transaction unchanged.
- ACK safety: no auto-ack change, no early ACK, no swallowed listener exceptions.
- Scope: only the seckill listener factory, consumer properties, consumer annotation, profiling/benchmark artifacts, and final report changed.
- Measurement: formal sample uses the unchanged benchmark and same warm-up/drain protocol; any missing metric is reported as missing rather than inferred.
