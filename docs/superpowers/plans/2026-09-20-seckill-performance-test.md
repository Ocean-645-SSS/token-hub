# Seckill Performance Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Measure the deployed TokenHub seckill reservation interface and its Redis-to-RabbitMQ-to-MySQL completion path under sustained load, without changing production business logic.

**Architecture:** A standalone benchmark harness will authenticate synthetic test users with locally signed test JWTs, issue reservation requests, capture client-side HTTP latency, and poll the existing result API to derive request-to-order-completion latency. Each isolated run resets only test activity data and Redis keys, then validates MySQL orders, local outbox messages, RabbitMQ backlog, and Redis reservation state.

**Tech Stack:** Spring Boot 3.3.13 / Java 17 service, Redis Lua, RabbitMQ, MySQL, PowerShell HTTP client or JMeter-compatible results.

## Global Constraints

- Do not change TokenHub core business code, Lua behavior, database schema, or middleware configuration merely to improve test results.
- Warm up before recorded measurements; each recorded concurrency level runs for at least 30 seconds unless the host is conclusively overloaded.
- Record HTTP interface latency separately from request-to-final-order persistence latency.
- Use unique users for every successful reservation attempt and retain raw benchmark artifacts and reset scripts for auditability.

---

### Task 1: Verify executable test environment and trace the live seckill path

**Files:**
- Read: `src/main/java/com/tokenhub/service/SeckillService.java`
- Read: `src/main/java/com/tokenhub/messaging/LocalMessagePublisher.java`
- Read: `src/main/java/com/tokenhub/messaging/SeckillOrderConsumer.java`
- Read: `src/main/java/com/tokenhub/service/SeckillOrderService.java`

- [ ] **Step 1: Check application and middleware reachability**

Run: `curl.exe -sS -o NUL -w "%{http_code}" http://127.0.0.1:8080/api/packages`

Expected: an HTTP response, then validate Redis, RabbitMQ, and MySQL connectivity through their existing deployment.

- [ ] **Step 2: Record runtime and configuration facts**

Run: `java -version`, inspect running JVM command line and Docker container metadata where permissions permit.

Expected: a reportable environment inventory, with unavailable facts explicitly marked unavailable.

### Task 2: Establish an auditable isolated benchmark fixture

**Files:**
- Read: `src/main/resources/db/tokenhub.sql`
- Read: `src/main/resources/lua/reserve_seckill_stock.lua`
- Create: `docs/performance/2026-09-20-seckill/` (raw artifacts and final report)

- [ ] **Step 1: Create a fresh active activity with a documented stock value and unique test users**

Use test-only records and namespaced Redis keys for its returned `activityId`; never alter application logic.

- [ ] **Step 2: Preheat through the existing service behavior and prove a one-user request reaches `ORDER_CREATED`**

Expected: Redis reservation stock decreases once, a `SECKILL_ORDER` local message is sent, and one MySQL `orders` row with the fixture activity ID exists.

### Task 3: Run sustained interface throughput measurements

**Files:**
- Create: `docs/performance/2026-09-20-seckill/` raw CSV/JTL equivalents

- [ ] **Step 1: Warm the JVM, Redis connections, AMQP publisher, and database pool**

Run a non-recorded sustained request interval with enough distinct fixture users.

- [ ] **Step 2: Execute recorded 30-second levels**

Run: concurrency `1, 10, 50, 100, 200, 500, 1000`; use fresh users and enough stock per level.

Expected: records contain total, accepted, failed, QPS, average, P50, P95, P99, maximum, and error rate.

- [ ] **Step 3: Probe around the first throughput/latency inflection point if observed**

Expected: enough evidence to identify the stable maximum and tail-latency knee, or state that host constraints prevent it.

### Task 4: Measure asynchronous completion and validate correctness

**Files:**
- Create: `docs/performance/2026-09-20-seckill/` completion and consistency artifacts

- [ ] **Step 1: Use a stock-100 activity and submit at least 1,000 distinct-user concurrent requests**

Expected: only up to 100 requests are accepted and final order results are observed or explicitly time out.

- [ ] **Step 2: Reconcile all durable and in-memory state after queue drain**

Check: initial stock, Redis remaining stock, accepted reservations, RabbitMQ consumer completion, MySQL seckill order count, duplicate `(user_id, activity_id)` rows, duplicate request IDs, and reservations without orders.

- [ ] **Step 3: Compute end-to-end percentiles**

Expected: client request timestamp to `SUCCESS:orderNo` timestamps, reported independently of HTTP latency.

### Task 5: Publish evidence-backed results

**Files:**
- Create: `docs/performance/2026-09-20-seckill/report.md`

- [ ] **Step 1: Summarize the tested environment, results table, bottlenecks, and correctness conclusion**

Expected: no invented capacity or consistency claims; every conclusion maps to collected data or a stated limitation.

- [ ] **Step 2: Preserve raw output and remove only disposable temporary files**

Expected: report reproduces the commands/fixture IDs sufficiently for review.
