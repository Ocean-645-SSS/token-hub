# Client Observation 根因定位实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在不改变任何业务逻辑和性能参数的前提下，拆解 `Server T11 → Client observes SUCCESS`，确认 Client E2E 与 Server E2E 的统计口径、轮询调度和 Result API 的真实贡献。

**Architecture:** 保持现有 benchmark 的 worker 数量、HTTP client、polling interval 和执行顺序不变，创建 profiling-only benchmark 副本记录 C0–C7 与每次 polling。服务端只增加 profiling 日志：Result API 记录 R0–R3，现有 `SeckillTrace` 增加可比较的 wall-clock T11 字段；通过 requestId 关联两个 JVM 的日志，禁止直接相减不同 JVM 的 `nanoTime`。

**Tech Stack:** JDK 17、Spring Boot 3.3.13、Spring MVC、Redis、RabbitMQ、现有 standalone Java benchmark、PowerShell runtime sampling。

## Global Constraints

- 本阶段只做 Profiling / 根因定位，不进行任何性能优化。
- 禁止修改 Publisher executor、Outbox poll/batch、Consumer concurrency/max/prefetch、ACK、Hikari maximumPoolSize、MySQL schema/index、Redis、Lua、Tomcat、JVM 参数、HTTP 接口行为和 benchmark 行为。
- 允许增加 profiling-only 日志、可选 profiling header 和独立分析副本；正式接口语义、返回值、轮询间隔、worker 数量、HTTP client 配置必须保持不变。
- 保持现有 `Client E2E` 定义，同时额外输出 `C7-C0`，不得为了改善数字修改原始统计口径。
- 同 JVM 只计算 `nanoTime` duration；跨 benchmark JVM 与应用 JVM 只使用 wall-clock timestamp 关联，无法可靠计算的阶段明确标记为不可计算。
- Profiling run 仅用于阶段归因，不直接作为严格性能回归结论。

---

### Task 1: 固化 benchmark 与服务端 Result API 的现状审计

**Files:**
- Read: `docs/performance/2026-09-20-seckill/SeckillBenchmark.java`
- Read: `src/main/java/com/tokenhub/controller/SeckillController.java`
- Read: `src/main/java/com/tokenhub/service/SeckillService.java`
- Read: `src/main/java/com/tokenhub/profiling/SeckillTrace.java`
- Create: `docs/performance/2026-09-21-client-observation/benchmark-audit.md`

**Interfaces:**
- Audit records exact source locations for C0/C1/C2, polling executor, HTTP client construction, timeout, 50ms sleep, percentile calculation and the current batch barrier before `pollCompletions`.
- No production or benchmark behavior changes in this task.

- [ ] **Step 1: Record the current client timing definition**

Document that the current accepted item stores `startedEpochMillis` immediately before each POST, while polling is invoked only after all POST worker futures complete; current `e2e.csv` is `System.currentTimeMillis() - startedEpochMillis` when SUCCESS is observed.

- [ ] **Step 2: Record executor and HTTP configuration from source**

Document:

```text
POST worker pool: Executors.newFixedThreadPool(concurrency)
polling pool: Executors.newFixedThreadPool(min(accepted.size(), 100))
poll interval: Thread.sleep(50ms) after a non-success GET
POST HttpClient: HTTP_1_1, connectTimeout=5s
poll HttpClient: default version, connectTimeout=5s
request timeout: POST=10s, GET=5s
custom HTTP executor/connection pool: none configured in source
```

- [ ] **Step 3: Record the service-side Result API path**

Document that `GET /api/seckill/result/{requestId}` calls Redis hash reads for status, userId, and orderNo, and does not query MySQL.

### Task 2: Add profiling-only server timestamps

**Files:**
- Create: `src/main/java/com/tokenhub/profiling/ResultApiTrace.java`
- Modify: `src/main/java/com/tokenhub/controller/SeckillController.java`
- Modify: `src/main/java/com/tokenhub/profiling/SeckillTrace.java`
- Test: `src/test/java/com/tokenhub/profiling/ResultApiTraceTest.java`

**Interfaces:**
- `ResultApiTrace.record(String requestId, int pollSequence, long r0WallMs, long r0Nanos, long r1Nanos, long r2Nanos, long r3Nanos, int statusCode, String businessState)` logs one `result-profile` line only when `tokenhub.profiling.enabled=true`.
- Optional request header `X-TokenHub-Poll-Sequence` is accepted only for correlation; absent or malformed values become `-1` and do not change response behavior.
- `SeckillTrace.finish` includes `finishedEpochMs` in its existing trace line so T11 can be matched across JVMs.

- [ ] **Step 1: Test disabled profiling has no emitted record**

Use the existing system-property-controlled style and assert the trace formatter contains requestId, poll sequence, R0–R3 durations and business state when enabled. Do not assert wall-clock exact values.

- [ ] **Step 2: Implement Result API timing around the existing service call**

```java
long r0Nanos = System.nanoTime();
long r0WallMs = System.currentTimeMillis();
long r1Nanos = System.nanoTime();
SeckillResultResponse response = service.result(requestId);
long r2Nanos = System.nanoTime();
long r3Nanos = System.nanoTime();
ResultApiTrace.record(requestId, pollSequence, r0WallMs, r0Nanos, r1Nanos, r2Nanos, r3Nanos,
        200, response.result());
return ApiResponse.success(response);
```

The existing service call and response remain unchanged. Exception responses are not fabricated as successful result traces.

- [ ] **Step 3: Add the absolute T11 field to existing profiling output**

Keep all existing relative markers and append `finishedEpochMs=System.currentTimeMillis()` to the `SeckillTrace.finish` output. Do not alter business state transitions.

- [ ] **Step 4: Run profiling-focused tests**

Run: `mvn "-Dtest=ResultApiTraceTest,SeckillServiceTest,SeckillOrderServiceTest" test`

Expected: PASS; no performance parameter changes.

### Task 3: Build an observation-only benchmark companion

**Files:**
- Create: `docs/performance/2026-09-21-client-observation/ClientObservationBenchmark.java`
- Do not modify: `docs/performance/2026-09-20-seckill/SeckillBenchmark.java`

**Interfaces:**
- The companion preserves the original POST worker count, warm-up/formal invocation, HTTP client builders, request timeouts, polling executor size, 50ms sleep, and batch barrier.
- It adds per-request `client-request-trace.csv`, per-GET `client-poll-trace.csv`, and `poll-executor.csv`.

- [ ] **Step 1: Copy the current benchmark control flow without changing behavior**

Retain `Executors.newFixedThreadPool(concurrency)`, shared POST HttpClient, `pollCompletions` after all POST futures, `Executors.newFixedThreadPool(Math.min(accepted.size(), 100))`, shared polling HttpClient, GET timeout 5s, and `Thread.sleep(50)` after non-success.

- [ ] **Step 2: Add client trace points**

For each accepted request record C0/C1/C2 at POST, C3 at polling task submission, C4 at callable entry, every C5/C6 with sequence/status/business state, and C7 immediately after parsing SUCCESS. Use `System.nanoTime()` for same-JVM durations and `System.currentTimeMillis()` for cross-process correlation.

- [ ] **Step 3: Add polling executor samples without changing its executor**

While polling is active, sample the actual `ThreadPoolExecutor` every 100ms: pool size, active count, queue size, completed task count, largest pool size and rejected count. Do not replace the executor or alter queue capacity.

- [ ] **Step 4: Compile and run a dry test against an unavailable endpoint**

Run the companion with a short duration and invalid base URL; verify it writes headers/trace failure rows without changing the original benchmark source.

### Task 4: Run the frozen profiling benchmark

**Files:**
- Create: `docs/performance/2026-09-21-client-observation/` raw CSV and application log artifacts.

**Interfaces:**
- Uses current Publisher + Consumer configuration exactly as installed.
- Produces 100 accepted request traces, Result API server traces, application profiling logs, Hikari samples and existing Rabbit/outbox evidence.

- [ ] **Step 1: Clean build and start the current application**

Run `mvn clean package -DskipTests`; start with `-Dtokenhub.profiling.enabled=true`, existing RabbitMQ/Redis, and an isolated temporary MySQL. Do not pass any changed performance parameter.

- [ ] **Step 2: Verify initial state**

Record outbox pending=0 and RabbitMQ ready=0/unacked=0 before warm-up; verify the configured Consumer remains `4/8/32` and Publisher remains `4/8/500`.

- [ ] **Step 3: Run unchanged warm-up and formal traffic**

Run the same 10-concurrency warm-up and 100-concurrency / 100-stock formal benchmark. Use the observation-only companion, not changed polling behavior.

- [ ] **Step 4: Wait for completion and capture consistency**

Record final orders, request/user uniqueness, reservation state, outbox statuses, Rabbit ready/unacked and Hikari profile. Stop the application and remove only the temporary MySQL after collection.

### Task 5: Analyze and write the root-cause report

**Files:**
- Create: `CLIENT_OBSERVATION_ROOT_CAUSE.md`
- Create: `docs/performance/2026-09-21-client-observation/analysis-summary.csv`

**Interfaces:**
- Computes client C1-C0, C2-C1, C4-C3, POST response→first poll, actual poll interval, poll count, C7-C6, Result API R3-R0, Redis lookup R2-R1, T11→first R0 after T11 and existing Client/Server E2E.
- Never subtracts `nanoTime` values across JVMs; uses wall-clock timestamps only for T11/R0 correlation and reports clock-resolution limits.

- [ ] **Step 1: Validate one-to-one request correlation**

Require 100 accepted requestIds, one C trace per request, all successful requests with a matching T11 and Result API SUCCESS response; report missing joins instead of filling values.

- [ ] **Step 2: Calculate all requested percentiles**

For every required metric output Avg/P50/P95/P99/Max; calculate poll count distribution and actual inter-poll intervals from C5 timestamps.

- [ ] **Step 3: Answer the ten required questions with evidence**

Classify whether the gap is caused by polling executor scheduling, 50ms sleep, POST batch barrier, HTTP acquisition, Result API latency, client parsing, or measurement artifact. If the benchmark’s batch barrier or start-time definition materially affects Client E2E, record the bug and impact direction but do not fix it.

- [ ] **Step 4: Run final checks**

Run `mvn test`, `mvn clean package -DskipTests`, and `git diff --check`. No optimization or parameter change is allowed after profiling results are known.
