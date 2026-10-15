# Stage 6 Delivery Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the documented local startup and JMeter acceptance path reproducible, then synchronize only evidence-backed checklist items.

**Architecture:** Docker Compose supplies MySQL, Redis, RabbitMQ, Elasticsearch, and a fixed OpenAI-compatible provider mock on local ports. README points to that environment and records the factual normal-purchase JMeter result; the JMeter plan reads all credentials from command-line properties.

**Tech Stack:** Docker Compose, MySQL 8, Redis 7, RabbitMQ 3, Elasticsearch 8, Python 3, Apache JMeter 5.6.3.

## Global Constraints

- Keep V1 as a Spring Boot 3 / JDK 17 monolith under `com.tokenhub`.
- Never commit plaintext API keys, JWTs, local tool binaries, or generated HTML report assets.
- Only check acceptance boxes with committed, reproducible evidence.

---

### Task 1: Reproducible local dependencies

**Files:**
- Create: `compose.yaml`
- Modify: `README.md`

- [x] **Step 1: Define five local services**

Create Compose services `mysql`, `redis`, `rabbitmq`, `elasticsearch`, and `provider-mock`; expose the ports consumed by `application.yaml`, mount `tokenhub.sql` only into MySQL initialization, and run the committed provider mock with Python.

- [x] **Step 2: Document startup and application environment**

Add `docker compose up -d` and the precise `TOKENHUB_DB_PASSWORD=tokenhub` / provider-base-url environment values before `mvn spring-boot:run`.

- [x] **Step 3: Validate Compose syntax**

Run: `docker compose config`

Expected: the resolved configuration lists all five services without validation errors.

### Task 2: JMeter property and evidence synchronization

**Files:**
- Modify: `docs/jmeter/tokenhub-v1.jmx`
- Modify: `docs/jmeter/README.md`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-09-19-tokenhub-v1-execution-checklist.md`

- [x] **Step 1: Read credentials only from JMeter properties**

Replace the static API-key value and the secondary `${jwt}` reference with `${__P(apiKey,REPLACE_WITH_TEST_API_KEY)}` and `${__P(jwt,REPLACE_WITH_LOGIN_TOKEN)}`.

- [x] **Step 2: Document factual result and limits**

Record the committed normal-purchase JTL values: 20 samples, 4.455 seconds, 4.49 samples/s, P95 39 ms, P99 139 ms, and 0% errors. State that no MySQL-versus-Redis/Lua/MQ comparison or capacity conclusion was made.

- [x] **Step 3: Check the evidence-backed Stage 6 JMeter acceptance item**

Mark only the JMeter-script/report acceptance line complete. Leave the README-reproducibility line unchecked until startup is independently run from a clean clone.

- [x] **Step 4: Validate artifacts**

Run: `[xml](Get-Content -Raw 'docs/jmeter/tokenhub-v1.jmx') | Out-Null; docker compose config; git diff --check`

Expected: valid XML, valid Compose configuration, and no whitespace errors in authored files.
