# TokenHub V1

TokenHub 是一个面向 AI 开发者的 Token 套餐购买与模型调用平台。它将普通购买、秒杀预占、异步履约、模型额度冻结结算、套餐搜索和 AI 导购放在一个 Spring Boot 单体中；V1 的重点是库存、账务和状态转换可验证，而非宣称未经测试的吞吐能力。

## 架构

```text
Client
  ├─ /api/* ── JWT ──> Controller → Service → MySQL (orders/wallet/ledger/local_message)
  │                                  ├─ Redis + Lua (seckill reservation / token freeze)
  │                                  ├─ RabbitMQ (seckill order / token grant / timeout)
  │                                  └─ Elasticsearch (token_package_index, search projection only)
  ├─ /v1/chat/completions ── API Key ─> ModelProvider → one OpenAI-compatible provider
  └─ /ai/recommend ──> ModelProvider intent extraction → validated criteria
                       ├─ clarification needed → ask user for concrete constraint
                       └─ Elasticsearch candidates → ModelProvider recommendation (template fallback on failure)
```

`/api/**` 使用 `{ "code", "message", "data" }`；`/v1/chat/completions` 保持 OpenAI-compatible 响应。MySQL 是套餐数据的唯一事实源，ES 只是搜索索引：搜索、列表与筛选读取 ES；下单、扣库存、价格和套餐状态校验读取 MySQL。AI 导购只把用户需求和 ES 候选套餐传给模型，不使用 Agent、向量库或额外知识库。

## 依赖与启动

需要 JDK 17、Maven、Docker Desktop，以及 Apache JMeter 5.6.3（仅压测）。仓库提供本地依赖编排：MySQL 8、Redis、RabbitMQ、Elasticsearch 和固定响应的 OpenAI-compatible Provider mock。

1. 在当前 PowerShell 会话设置本机 MySQL root 密码后，启动依赖并等待 MySQL、RabbitMQ、Elasticsearch 的健康检查通过：

   ```powershell
   $env:TOKENHUB_MYSQL_ROOT_PASSWORD = '<local MySQL root password>'
   docker compose up -d --wait
   docker compose ps
   ```

   MySQL 初始化时会自动执行 `src/main/resources/db/tokenhub.sql`。本地测试数据库账户为 `root`，密码取自当前会话的 `TOKENHUB_MYSQL_ROOT_PASSWORD`；演示登录账户为 `demo / password`。仅用于本机开发，不能用于生产环境。

2. 通过环境变量配置应用：

   ```powershell
   $env:TOKENHUB_DB_PASSWORD = $env:TOKENHUB_MYSQL_ROOT_PASSWORD
   $env:TOKENHUB_MODEL_BASE_URL = 'http://127.0.0.1:18080'
   $env:TOKENHUB_MODEL_API_KEY = 'local-mock-key'
   ```

   其他可用变量包括 `TOKENHUB_DB_URL`、`TOKENHUB_REDIS_HOST`、`TOKENHUB_RABBITMQ_HOST`、`TOKENHUB_ES_URL`、`TOKENHUB_JWT_SECRET`。如需启用套餐管理写接口，将管理员用户 ID 配置到 `TOKENHUB_ADMIN_USER_IDS`，多个 ID 用逗号分隔；默认空值表示不授予任何管理员。完整默认值见 `src/main/resources/application.yaml`。

3. 启动和验证：

   ```powershell
   mvn test
   mvn spring-boot:run
   ```

3. 安装 Apache JMeter 5.6.3 并确认 `jmeter --version` 可用；随后可按 [JMeter 运行说明](docs/jmeter/README.md)执行三个场景之一。

## 接口示例

登录并保存返回的 `data.accessToken`：

```powershell
curl.exe -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"demo","password":"<test password>"}'
```

查询套餐与创建普通订单：

```powershell
curl.exe http://127.0.0.1:8080/api/packages/search?keyword=开发
curl.exe -X POST http://127.0.0.1:8080/api/orders -H 'Idempotency-Key: purchase-demo-1' -H 'Authorization: Bearer <JWT>' -H 'Content-Type: application/json' -d '{"packageId":1}'
curl.exe -X POST http://127.0.0.1:8080/api/orders/<orderId>/pay -H 'Authorization: Bearer <JWT>'
```

管理员套餐写接口为 `POST /api/admin/packages`、`PUT /api/admin/packages/{id}` 和 `DELETE /api/admin/packages/{id}`（软下架）。请求需 JWT，且登录用户 ID 必须在 `TOKENHUB_ADMIN_USER_IDS` 白名单中。写接口在一个 MySQL 事务中保存套餐和 Outbox 事件，返回后 ES 更新异步完成，因此搜索索引可短暂滞后；消费者按最多 `TOKENHUB_PACKAGE_INDEX_MAX_ATTEMPTS` 次（默认 3 次）重试，耗尽后进入持久化 `package.index.dlq`，由运维检查/重放。应用启动时及每隔 `TOKENHUB_PACKAGE_INDEX_RECONCILIATION_INTERVAL_MS`（默认 60 秒）执行全量对账。每次可搜索字段或上下架变更都会递增 MySQL `catalog_version`，ES 使用 external version 写入；较旧版本无法覆盖新版本。下架写入带版本的 inactive tombstone，避免旧消息复活已下架套餐。通过管理 API 写入可由对账修复事件失败和短暂故障导致的差异；直接 SQL 修改套餐搜索字段时必须同时递增 `catalog_version`。已有数据库升级时，先执行 `src/main/resources/db/tokenhub.sql` 中的幂等列迁移，再启动新版本应用。

```text
MySQL catalog + Outbox (same transaction)
        → existing Outbox Publisher (RabbitMQ publisher confirm)
        → package.index.queue → idempotent Consumer → Elasticsearch
                                  └─ retries exhausted → package.index.dlq
Periodic MySQL ↔ Elasticsearch reconciliation repairs any remaining drift
```

ES 索引不能用于交易判定。即使搜索结果短暂过期，下单服务仍会按套餐 ID 回 MySQL 校验有效状态、价格和库存，并在 MySQL 中条件扣减库存。

创建 API Key 后，使用它调用模型（Key 仅在创建响应中出现一次）：

```powershell
curl.exe -X POST http://127.0.0.1:8080/v1/chat/completions -H 'Authorization: Bearer <API_KEY>' -H 'Idempotency-Key: demo-001' -H 'Content-Type: application/json' -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"max_tokens":32}'
```

AI 导购请求中的数值是可审查的用量假设；省略用量字段时返回“无法估算”的明确说明：

```powershell
curl.exe -X POST http://127.0.0.1:8080/ai/recommend -H 'Content-Type: application/json' -d '{"requirement":"代码助手","budget":100,"dailyRequests":100,"inputTokensPerRequest":2000,"outputTokensPerRequest":1000,"days":30}'
```

## 五条链路与状态机

1. 普通购买：MySQL 条件扣 `token_package.stock` 并创建 `WAIT_PAY`；支付后由本地消息和 RabbitMQ 异步发放 Token，Wallet 与唯一 Ledger 入账。
2. 秒杀：Redis Lua 检查活动、库存与一人一单，返回 `QUEUING`；MQ 消费者幂等创建订单，结果查询返回 `SUCCESS:orderNo` 或 `FAILED`。
3. 取消和超时：仅 `WAIT_PAY` 能变更；本地消息幂等回补普通 MySQL 库存或秒杀 Redis 库存。秒杀参与记录保留，不能重抢。
4. 模型计费：API Key 鉴权、Redis 冻结、单 Provider 调用、真实 `usage.total_tokens` 结算；失败释放冻结，缺 usage 标记 `BILLING_UNKNOWN`。
5. 搜索与导购：LLM 将自然语言需求转为受限 JSON 筛选条件；后端校验字段、数值范围、预算上限，并从 MySQL 检查指定 `modelCode` 是否有在售套餐。若模型代码没有对应套餐，接口返回澄清问题并暂停检索，不静默移除用户偏好；其他模糊且会影响筛选的条件也会澄清，普通可选条件未填写时继续检索。意图解析最多调用 `tokenhub.model.max-attempts` 次（默认 3 次）：可重试的 Provider 异常重试，结构校验失败则把校验原因交给 LLM 修正；最终失败回退到原始需求关键词和预算。ES 执行关键词/模型/价格/Token 范围检索，推荐文案只基于 ES 候选生成；推荐阶段最终失败时由后端按候选套餐事实生成模板说明。

```text
101 WAIT_PAY ──pay──> 201 PAID → 301 GRANTING → 302 GRANTED → 401 COMPLETED
     ├─cancel──────> 102 USER_CANCELLED
     └─timeout─────> 103 TIMEOUT_CANCELLED
```

每次状态更新均带旧状态条件；只有 `affectedRows == 1` 才创建后续 `local_message`。`local_message` 与状态更新处于同一 MySQL 事务，消费者和账本以业务标识幂等。

## 压测

可复跑 JMeter 脚本和运行说明见 [docs/jmeter/README.md](docs/jmeter/README.md)，真实结果记录见 [docs/jmeter/results/2026-09-19-stage-6.md](docs/jmeter/results/2026-09-19-stage-6.md)。脚本包含普通购买、秒杀预占/异步结果和受控 Provider mock 的模型计费三个场景。

已记录的普通购买小样本为 10 线程、每线程 1 次循环：20 个样本在 4.455 秒内完成，吞吐量 4.49 samples/s、P95 39 ms、P99 139 ms、错误率 0%。这只是本机单场景结果；尚未执行 MySQL 与 Redis + Lua + MQ 的负载对比，也不构成容量结论。

## 已知限制

V1 不实现退款、SSE、微服务/Spring Cloud、真实支付、多 Provider 路由、多租户、复杂会员、向量库、GraphRAG、多 Agent 或复杂推荐算法。Elasticsearch、RabbitMQ、Redis 与 Provider 是外部依赖；它们不可用时，相关功能不能被当作已验证可用。

### 普通购买幂等与订单关闭

`POST /api/orders` 必须携带 `Idempotency-Key`（1–64 位 ASCII 字母、数字、下划线或连字符）。同一次购买重试复用原键，同用户同键返回原订单，换套餐返回 `IDEMPOTENCY_KEY_CONFLICT`；主动复购使用新键。已有数据库先执行 `src/main/resources/db/migrations/2026-09-29-order-purchase-idempotency.sql`（可重复执行，不修改已有订单或初始化数据）。订单插入、条件扣库存和超时 Outbox 同事务提交。
普通和秒杀订单均写入 `ORDER_TIMEOUT`，经 RabbitMQ TTL + DLX 检查关闭；取消与超时仅允许从 `WAIT_PAY` 迁移，成功后按订单类型回补 MySQL 普通库存或 Redis 秒杀库存。取消接口提交事件后返回，库存异步回补，秒杀参与记录保留。
