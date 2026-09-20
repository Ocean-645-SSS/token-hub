# TokenHub V1

TokenHub 是一个面向 AI 开发者的 Token 套餐购买与模型调用平台。它将普通购买、秒杀预占、异步履约、模型额度冻结结算、套餐搜索和 AI 导购放在一个 Spring Boot 单体中；V1 的重点是库存、账务和状态转换可验证，而非宣称未经测试的吞吐能力。

## 架构

```text
Client
  ├─ /api/* ── JWT ──> Controller → Service → MySQL (orders/wallet/ledger/local_message)
  │                                  ├─ Redis + Lua (seckill reservation / token freeze)
  │                                  ├─ RabbitMQ (seckill order / token grant / timeout)
  │                                  └─ Elasticsearch (token_package_index)
  ├─ /v1/chat/completions ── API Key ─> ModelProvider → one OpenAI-compatible provider
  └─ /ai/recommend ───────────────────> ES candidates → ModelProvider recommendation
```

`/api/**` 使用 `{ "code", "message", "data" }`；`/v1/chat/completions` 保持 OpenAI-compatible 响应。AI 导购只把用户需求和 ES 候选套餐传给模型，不使用 Agent、向量库或额外知识库。

## 依赖与启动

需要 JDK 17、Maven、MySQL 8、Redis、RabbitMQ、Elasticsearch 和（仅模型调用/导购需要）一个 OpenAI-compatible Provider。当前仓库不提供 Docker Compose 编排，须先自行启动这些依赖。

1. 创建并初始化数据库：

   ```powershell
   mysql -u root -p < src/main/resources/db/tokenhub.sql
   ```

2. 通过环境变量配置服务。最小本地示例：

   ```powershell
   $env:TOKENHUB_DB_PASSWORD = '<mysql password>'
   $env:TOKENHUB_MODEL_BASE_URL = 'http://127.0.0.1:18080'
   $env:TOKENHUB_MODEL_API_KEY = '<provider key>'
   ```

   其他可用变量包括 `TOKENHUB_DB_URL`、`TOKENHUB_REDIS_HOST`、`TOKENHUB_RABBITMQ_HOST`、`TOKENHUB_ES_URL`、`TOKENHUB_JWT_SECRET`。完整默认值见 `src/main/resources/application.yaml`。

3. 启动和验证：

   ```powershell
   mvn test
   mvn spring-boot:run
   ```

SQL 会创建测试用户 `demo`。请使用与该初始化哈希匹配的本地测试密码，或自行更新其 `password_hash`；仓库不在文档中提供可用于外部环境的凭据。

## 接口示例

登录并保存返回的 `data.accessToken`：

```powershell
curl.exe -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"demo","password":"<test password>"}'
```

查询套餐与创建普通订单：

```powershell
curl.exe http://127.0.0.1:8080/api/packages/search?keyword=开发
curl.exe -X POST http://127.0.0.1:8080/api/orders -H 'Authorization: Bearer <JWT>' -H 'Content-Type: application/json' -d '{"packageId":1}'
curl.exe -X POST http://127.0.0.1:8080/api/orders/<orderId>/pay -H 'Authorization: Bearer <JWT>'
```

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
5. 搜索与导购：ES 进行关键词/模型/价格/Token 范围检索；导购仅从这批候选给出理由。

```text
101 WAIT_PAY ──pay──> 201 PAID → 301 GRANTING → 302 GRANTED → 401 COMPLETED
     ├─cancel──────> 102 USER_CANCELLED
     └─timeout─────> 103 TIMEOUT_CANCELLED
```

每次状态更新均带旧状态条件；只有 `affectedRows == 1` 才创建后续 `local_message`。`local_message` 与状态更新处于同一 MySQL 事务，消费者和账本以业务标识幂等。

## 压测

可复跑 JMeter 脚本和运行说明见 [docs/jmeter/README.md](docs/jmeter/README.md)，真实结果记录见 [docs/jmeter/results/2026-09-19-stage-6.md](docs/jmeter/results/2026-09-19-stage-6.md)。脚本包含普通购买、秒杀预占/异步结果和受控 Provider mock 的模型计费三个场景。当前未采集本地压测指标，因此没有 p95/p99、吞吐量或 MySQL 与 Redis+Lua+MQ 的性能对比结论。

## 已知限制

V1 不实现退款、SSE、微服务/Spring Cloud、真实支付、多 Provider 路由、多租户、复杂会员、向量库、GraphRAG、多 Agent 或复杂推荐算法。Elasticsearch、RabbitMQ、Redis 与 Provider 是外部依赖；它们不可用时，相关功能不能被当作已验证可用。
