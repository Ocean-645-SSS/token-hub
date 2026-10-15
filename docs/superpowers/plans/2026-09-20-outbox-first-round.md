# Outbox First-round Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce reliable seckill outbox delivery latency and increase sustained publish capacity without weakening Redis reservation, RabbitMQ confirms, retry, or consumer idempotency.

**Architecture:** Add configurable polling/batching/in-flight limits, atomically claim rows as `SENDING`, publish under a bounded permit count, and finish each row asynchronously from its correlated publisher-confirm future. Reclaim stale claims after a process crash; duplicate delivery remains safe because requestId/order unique constraints and consumer checks are unchanged.

**Tech Stack:** Java 17, Spring Boot 3.3.13, Spring AMQP correlated publisher confirms, MyBatis annotations, MySQL 8, Redis, RabbitMQ.

## Global Constraints

- Preserve Redis Lua stock reservation, transactional `local_message`, RabbitMQ, confirms, retry, requestId idempotency, and eventual consistency.
- Do not replace asynchronous order creation with synchronous database order creation.
- Bound asynchronous in-flight confirms; do not create unbounded threads.
- Test HTTP throughput and end-to-end completion against the prior benchmark’s methods and retain raw artifacts.

---

### Task 1: Add explicit outbox lifecycle and configuration

**Files:**
- Create: `src/main/java/com/tokenhub/config/MessagingProperties.java`
- Modify: `src/main/java/com/tokenhub/TokenHubApplication.java`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/java/com/tokenhub/mapper/LocalMessageMapper.java`
- Modify: `src/main/resources/db/tokenhub.sql`

**Interfaces:**
- `MessagingProperties(long pollIntervalMs, int batchSize, int maxInFlight, int claimTimeoutSeconds, int retryDelaySeconds)` provides validated configuration.
- Mapper adds `int claim(long id)`, `int markSentClaimedById(long id)`, and `int recoverExpiredClaims(int claimTimeoutSeconds, int retryDelaySeconds)`.

- [ ] **Step 1: Write mapper/publisher tests for claim and confirm outcomes**

```java
when(mapper.claim(7L)).thenReturn(1);
publisher.publishPending();
confirm.complete(new CorrelationData.Confirm(true, null));
verify(mapper).markSentClaimedById(7L);
```

- [ ] **Step 2: Implement statuses and claims**

Use status `3` as `SENDING`; only pending/retry-due rows can transition to it. A stale `SENDING` row becomes retryable with retry_count incremented and next_retry_time set.

- [ ] **Step 3: Add named configuration defaults**

Use `poll-interval-ms=200`, `batch-size=500`, `max-in-flight=1000`, `claim-timeout-seconds=30`, and `retry-delay-seconds=5`; all values remain environment-overridable.

- [ ] **Step 4: Add `idx_message_publish(status,next_retry_time,id)` to fresh schema**

Keep the existing index for compatibility; apply the equivalent index only to the local benchmark database before testing.

### Task 2: Replace serial confirm waits with bounded asynchronous confirms

**Files:**
- Modify: `src/main/java/com/tokenhub/messaging/LocalMessagePublisher.java`
- Test: `src/test/java/com/tokenhub/messaging/LocalMessagePublisherTest.java`

**Interfaces:**
- `publishPending()` first recovers stale claims, then scans one configured batch and claims each row before publishing.
- `publish(LocalMessage)` sends one correlated message without blocking and completes its durable state in `whenComplete`.

- [ ] **Step 1: Write failing tests**

```java
@Test void negativeConfirmSchedulesRetryAndReleasesPermit() { /* complete confirm(false, "nack") */ }
@Test void unclaimedRowIsNotPublished() { /* claim returns 0 */ }
```

- [ ] **Step 2: Implement bounded publish and confirm callback**

Acquire a semaphore permit before claim/send. On acknowledged confirm update only status-3 rows to sent; on exception/nack schedule retry. Always release the permit.

- [ ] **Step 3: Preserve Redis and consumer ordering safety**

Set reservation status to `SENT` after claim and before broker send; do not overwrite `ORDER_CREATED` from an asynchronous callback. Keep consumer’s `PENDING/SENT` acceptance and MySQL unique constraints unchanged.

- [ ] **Step 4: Run focused tests and all Maven tests**

Run: `mvn -Dtest=LocalMessagePublisherTest test`, then `mvn test`.

### Task 3: Re-run A/B test and publish report

**Files:**
- Create: `docs/performance/2026-09-20-outbox-optimization/OUTBOX_OPTIMIZATION_REPORT.md`
- Create: raw CSV results under the same directory

- [ ] **Step 1: Build and launch the app with local middleware**

Use an isolated activity per run and preheat the existing Redis keys; do not change business logic for fixtures.

- [ ] **Step 2: Run warm-up and 30-second HTTP levels**

Run concurrency 1, 10, 50, 100, 200, 300, 500; record QPS and all requested percentiles.

- [ ] **Step 3: Run 100/500/1000 accepted-order E2E probes and stock-100 correctness test**

Capture request start to `SUCCESS:orderNo`, outbox sent/retry state, RabbitMQ ready/unacked, Redis stock/users, and MySQL uniqueness counts.

- [ ] **Step 4: Compare only identical measurement definitions with the baseline**

Report before/after values, percentage changes, residual bottleneck, and a factual interview explanation.
