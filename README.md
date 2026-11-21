# TokenHub V1

TokenHub 是一个面向 AI 开发者的 Token 套餐购买与模型调用平台。它将普通购买、秒杀预占、异步履约、模型额度冻结结算、套餐搜索和 AI 导购放在一个 Spring Boot 单体中；V1 的重点是库存、账务和状态转换可验证，而非宣称未经测试的吞吐能力。

## 架构

```text
Client
  ├─ /api/* ── JWT ──> Controller → Service → MySQL (orders/wallet/ledger/local_message)
  │                                  ├─ Redis + Lua (seckill reservation)
  │                                  ├─ RabbitMQ (seckill order / token grant / timeout)
  │                                  └─ Elasticsearch (token_package_index, search projection only)
  ├─ /v1/chat/completions ── API Key ─> ProviderRouter → upstream adapter
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

## 模型 API 接入

本地接入地址为 `http://127.0.0.1:8080`。`POST /v1/chat/completions` 使用 OpenAI Chat Completions 格式，支持非流式调用。`model` 和 `messages` 必填，可传 `max_tokens`、`tools`，`stream` 可省略或设为 `false`。

1. 登录后购买所需模型的套餐并支付，等 Token 到账后，通过 `GET /api/wallet` 查看余额。本地示例使用种子套餐的 `modelCode` `gpt-4o-mini`。
2. 使用登录 JWT 创建调用 Key。`allowedModels` 填写已购买且已到账的模型代码。保存响应中的 `data.key`，它只在创建响应中出现。可通过 `GET /api/api-keys` 查看已有 Key 的 `allowedModels`。当前没有 `GET /v1/models`，模型代码可从套餐查询结果获取，实际可调用范围还取决于上游配置。

   ```powershell
   curl.exe -X POST http://127.0.0.1:8080/api/api-keys -H 'Authorization: Bearer <JWT>' -H 'Content-Type: application/json' -d '{"name":"local-demo","allowedModels":["gpt-4o-mini"]}'
   ```

3. 使用创建的 TokenHub API Key 调用模型。每次新调用生成一个非空、最长 64 字符的 `Idempotency-Key`。使用 OpenAI SDK 时也要显式附带该请求头。

```powershell
$requestId = [guid]::NewGuid().ToString()
curl.exe -X POST http://127.0.0.1:8080/v1/chat/completions -H 'Authorization: Bearer <API_KEY>' -H "Idempotency-Key: $requestId" -H 'Content-Type: application/json' -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}],"max_tokens":32}'
```

本地 mock 的成功响应如下，文本位于 `choices[0].message.content`，本次结算用量为 `usage.total_tokens`。

```json
{
  "id": "mock-completion",
  "object": "chat.completion",
  "choices": [{"index": 0, "message": {"role": "assistant", "content": "Controlled mock response."}, "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}
}
```

### 重试与计费

用登录 JWT 请求 `GET /api/api-keys/{id}/usage`，可查看自己的 Key 最近 7 天的用量。`id` 来自 Key 列表，即使已撤销也可以查历史。

- `data.requestCount` 统计最近 7 天创建的模型调用记录。同一幂等请求只计一次，上游重试和切换也记在同一条记录里。
- `data.settledTokens` 汇总上述请求中成功结算的 Token。没有调用记录时两个指标均为 `0`。

请求开始时冻结一次额度。成功后按最终响应的 `usage.total_tokens` 结算一次，从 API Key 所属用户、原请求 `model` 对应的 Token 钱包扣除。可以用登录 JWT 调用 `GET /api/wallet/ledger` 查看消费流水。

| 情况 | 客户端处理 |
| --- | --- |
| 客户端超时或未收到响应 | 用同一个 API Key、原请求参数和原幂等键重发。若已成功，返回原结果，消费只记一次。 |
| `409 REQUEST_PROCESSING` | 等待后复用原键。 |
| `429 RATE_LIMIT_EXCEEDED` | 每个 Key 使用独立的 Redis 令牌桶。默认每秒补 5 个令牌，最多存 5 个。等待后复用原幂等键，可避免客户端重发时重复执行。速率和容量都由 `TOKENHUB_RATE_LIMIT_API_KEY_QPS` 设置。 |
| `503 MODEL_TEMPORARILY_UNAVAILABLE` | 该次请求失败并释放冻结。要重新发起调用，使用新键。 |
| `502 BILLING_UNKNOWN` | 系统释放该次请求冻结的额度。复用原键会返回同一状态，换键会发起一次新调用。 |
| `401 INVALID_API_KEY`、`403 MODEL_NOT_ALLOWED`、`402 CREDIT_LIMIT_EXCEEDED` | 分别检查 Key、模型权限和该模型钱包的可用额度。 |

### 上游配置与兼容范围

Spring Boot 会从启动目录读取可选的 `.env`，格式见 [`.env.example`](.env.example)。也可使用上文的 PowerShell 环境变量配置。如果已有 `.env`，本地演示前确认其中的 `tokenhub.model.base-url` 指向 mock。`.env` 已被 Git 忽略。

- **本地演示**使用 Compose 内的 mock。`TOKENHUB_MODEL_API_KEY=local-mock-key` 是占位值，无需购买或填写 OpenAI、DeepSeek、Anthropic 的密钥。示例的 `gpt-4o-mini` 是本地套餐和路由使用的模型代码，mock 返回固定内容。
- **接一个真实上游**时，为 Spring Boot 进程设置 `TOKENHUB_MODEL_BASE_URL` 和 `TOKENHUB_MODEL_API_KEY`，填所选上游的接入地址和凭据。OpenAI-compatible 适配器会追加 `/v1/chat/completions`，Base URL 应填写追加这个路径前的地址。默认单上游会把请求的模型代码原样发送，因此该代码需要是上游接受的模型 ID。选用兼容网关时使用该网关颁发的 Key。调用方仍使用 TokenHub 发放的 Key。
- **配置多个上游或模型映射**时，参照 [`tokenhub.model.providers`](src/main/resources/application.yaml) 的示例，为每个上游配置地址、凭据和所需的 `models` 映射。仓库实现了 OpenAI-compatible Chat Completions 和 Anthropic Messages 两种上游适配器，后者要求显式映射模型 ID。OpenAI、DeepSeek 等是符合相应请求和用量格式时的接入候选，实际部署支持的模型以已配置并实测的上游为准。

存在同一对外模型的备用上游时，明确的限流、过载或重试耗尽后的连接失败可触发切换。读取超时、结果不明的 `5xx` 或缺少有效用量时，请求以 `BILLING_UNKNOWN` 结束。

## AI 导购接口

AI 导购请求中的数值是可审查的用量假设；省略用量字段时返回“无法估算”的明确说明：

```powershell
curl.exe -X POST http://127.0.0.1:8080/ai/recommend -H 'Content-Type: application/json' -d '{"requirement":"代码助手","budget":100,"dailyRequests":100,"inputTokensPerRequest":2000,"outputTokensPerRequest":1000,"days":30}'
```

## 五条链路与状态机

1. 普通购买：MySQL 条件扣 `token_package.stock` 并创建 `WAIT_PAY`；支付后由本地消息和 RabbitMQ 异步发放 Token，Wallet 与唯一 Ledger 入账。
2. 秒杀：Redis Lua 检查活动、库存与一人一单，返回 `QUEUING`；MQ 消费者幂等创建订单，结果查询返回 `SUCCESS:orderNo` 或 `FAILED`。
3. 取消和超时：仅 `WAIT_PAY` 能变更；本地消息幂等回补普通 MySQL 库存或秒杀 Redis 库存。秒杀参与记录保留，不能重抢。
4. 模型计费：API Key 鉴权、MySQL 事务内冻结和结算，按最终成功响应的 `usage.total_tokens` 扣除用户额度。默认单上游，配置多个兼容上游后可在特定错误下切换；请求失败或计费未知时释放冻结。
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
