# TokenHub V1 执行清单 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实施。本清单中的复选框用于跟踪；每次仅执行用户明确指定的一个阶段。

**目标：** 将现有点评后端逐阶段改造成可演示、可验证的高并发 Token 购买平台，并以库存、账务、幂等与状态迁移正确性为验收核心。

**实施方式：** 采用 JDK 17（2026-09-19 经用户确认替代 JDK 21）、Spring Boot 3.x 单体，分层保持 Controller → Service → Mapper。普通订单以 MySQL 事务为主；秒杀使用 Redis + Lua 预占与 RabbitMQ 异步落单；所有数据库状态变化后的异步副作用通过 `local_message` 可靠投递。

**技术栈：** JDK 17、Spring Boot 3.x、MySQL、MyBatis/MyBatis-Plus、Redis + Lua、RabbitMQ、Elasticsearch、一个 OpenAI-Compatible Provider、Docker Compose、JMeter。

## 全局约束

- 包名固定为 `com.tokenhub`；不沿用 `com.hmdp` 作为新业务包名。
- `/api/**` 响应固定为 `{ code, message, data }`；`/v1/chat/completions` 保持 OpenAI-Compatible 格式。
- 每项状态迁移都必须携带原状态条件；只有 `affectedRows == 1` 才获得写副作用事件的资格。
- `local_message` 与对应状态更新必须处于同一 MySQL 事务；事件发布和消费均可重试且业务副作用必须幂等。
- 普通库存仅使用 `token_package.stock`；秒杀库存仅使用 `seckill_activity.stock` 与 Redis 活动库存，二者不得混用。
- V1 不实现退款、SSE、微服务、Spring Cloud/Gateway/Nacos/Sentinel/Seata、多租户、复杂会员、真实支付、多 Provider 路由、向量库、GraphRAG、多 Agent。
- 未定的 MQ 拓扑、ES mapping/分词/同步方式、请求 DTO、分页格式、JWT claim 等，须在其所属阶段先形成可审查决定；不得自行扩展需求。
- 每阶段结束只报告：变更内容、已执行验证与结果、尚未确定项；未运行或未压测的内容必须明确标记。

---

## 实施对象与文件边界

| 区域 | 主要路径 | 职责与边界 |
|---|---|---|
| 构建与启动 | `pom.xml`、`src/main/java/com/tokenhub/TokenHubApplication.java`、`src/main/resources/application.yaml` | 升级运行时和依赖，提供单体启动入口及外部化配置。 |
| 数据库与初始化 | `src/main/resources/db/tokenhub.sql` | TokenHub 的 DDL、唯一约束、测试用户和最小演示数据；不继续修改 `hmdp.sql`。 |
| 业务分层 | `src/main/java/com/tokenhub/{controller,service,mapper,entity,dto,config,common}` | 承载用户、套餐、订单、秒杀、钱包、API Key、模型调用、搜索与导购模块。 |
| Redis / Lua | `src/main/resources/lua/*.lua` | 秒杀资格预占、秒杀库存回补、Token 冻结/结算/释放的原子操作。 |
| 消息与事件 | `src/main/java/com/tokenhub/messaging/**`、`src/main/java/com/tokenhub/event/**` | RabbitMQ 拓扑、消费者、`local_message` 发布与重试、延迟取消。 |
| 搜索与模型 | `src/main/java/com/tokenhub/search/**`、`src/main/java/com/tokenhub/model/**` | 套餐索引、检索、单一 Provider 抽象、冻结与计费。 |
| 测试与压测 | `src/test/java/com/tokenhub/**`、`docs/jmeter/**`、`README.md` | 单元/集成测试、关键竞争场景、可复现压测记录和演示说明。 |

旧 `src/main/java/com/hmdp/**` 仅能作为迁移参考。每个阶段应明确删除、迁移或保留的旧文件；不允许以“改显示名称”替代业务模型替换。

## 阶段 0：改造基线与可运行骨架

**完成任务**

- [x] 记录改造前的分支、工作区状态、`pom.xml` 依赖、现有测试结果和本地服务可用性；这些记录仅描述事实，不把未运行项目标为正常。
- [x] 将构建运行时升级至 JDK 17（用户确认替代 JDK 21）、Spring Boot 3.x，并将应用坐标、启动类和 Java 包迁移为 TokenHub / `com.tokenhub`。
- [x] 建立统一异常处理、`/api/**` 响应包装、请求日志/必要配置骨架，以及简单 JWT access token 登录链路；不加入 refresh token 或注册流程。
- [x] 写出 `tokenhub.sql` 的可执行初版：`user`、`token_package`、`orders`、`token_wallet`、`token_ledger`、`seckill_activity`、`api_key`、`model_request`、`local_message`；实现规定的唯一索引和测试用户。
- [x] 落实固定枚举：订单状态 `101/102/103/201/301/302/401`、订单类型 `NORMAL/SECKILL`、账本类型、模型请求状态与成本状态；任何退款枚举均不得出现。
- [x] 实现套餐列表、详情、分页搜索接口的最小 MySQL 版本，以及 `PACKAGE_NOT_FOUND` 等已定义业务错误。

**进入条件**

- 用户确认当前要开始“骨架 + MySQL”阶段；数据库连接信息和 JWT 密钥提供方式已确定。

**验收**

- [x] 从干净数据库执行 `tokenhub.sql` 成功；九张表、测试用户和规定唯一索引均存在。
- [x] `mvn test` 与应用启动检查通过；启动日志显示 TokenHub 启动类，不再加载 `com.hmdp` 业务 Bean。
- [x] `POST /api/auth/login` 能取得 access token；未携带或携带无效 token 访问受保护 `/api` 接口会被拒绝。
- [x] `GET /api/packages`、`GET /api/packages/{id}`、`GET /api/packages/search` 能返回约定包装；不存在套餐返回 `PACKAGE_NOT_FOUND`。
- [x] 数据库检查证明 `orders` 的普通订单允许 `seckill_activity_id = NULL`，秒杀订单的活动维度唯一约束生效。

**阶段产物**

- 可启动的 TokenHub 单体、可重复执行的初始化 SQL、套餐查询接口、阶段验证记录。

## 阶段 1：普通购买、支付与发放账务

**完成任务**

- [ ] 定义普通下单、支付、取消、订单详情的请求/响应 DTO 与错误语义；下单只允许可售套餐。
- [ ] 在一个 MySQL 事务中以条件更新扣减 `token_package.stock` 并创建 `WAIT_PAY` 的 `NORMAL` 订单；库存不足或并发失败必须整体回滚。
- [ ] 实现用户取消：仅 `101 → 102` 成功时，在同一事务创建 `NORMAL_ORDER_STOCK_RELEASE(orderNo)` 的 `local_message`。
- [ ] 实现模拟支付：仅 `101 → 201` 成功时，在同一事务创建 Token 发放事件；支付不得直接修改钱包或写入到账流水。
- [ ] 实现 Token 发放处理器：`201 → 301 → 302 → 401`，按 `(user_id, model_code)` 更新钱包并以 `TOKEN_GRANT_ORDER_<orderNo>` 写唯一账本；重复投递不可重复加额。
- [ ] 实现 `GET /api/orders/{id}`、`GET /api/wallet`、`GET /api/wallet/ledger`，确保仅能查看本人数据。

**进入条件**

- 阶段 0 的表、认证、套餐查询已验收；普通订单的订单号格式与模拟支付触发方式已确定。

**验收**

- [ ] 一次正常流程可得到 `WAIT_PAY → PAID → GRANTING → GRANTED → COMPLETED`，钱包可用额度增加，且只有一条 `PURCHASE` 流水。
- [ ] 并发对同一库存下单不会超卖；订单数与已扣库存的差值为零。
- [ ] 对同一订单重复支付、重复发放消息、重复执行本地事件，余额和 `token_ledger` 均不重复变化。
- [ ] 用户取消仅能发生一次，库存最终回补一次；支付与取消竞争后订单只落在一个合法终态/后续链路中。
- [ ] 取消、支付、发放所有状态更新均有“旧状态条件 + affectedRows 断言”的自动化测试。

**阶段产物**

- 普通购买到 Token 到账的完整链路、订单/钱包/账本查询、幂等与竞争测试。

## 阶段 2：Redis + Lua 秒杀资格预占

**完成任务**

- [ ] 实现秒杀活动管理所需的最小初始化数据与活动状态校验；活动库存从普通库存独立划出，两个库存字段不复用。
- [ ] 实现活动预热：写入 `seckill:activity:{id}`、`seckill:stock:{id}`；活动期间核心 Key 无自然过期，结束后由业务清理。
- [ ] 编写秒杀 Lua：原子校验活动时间/状态、Redis 库存与 `seckill:users:{id}`；通过后扣一份活动库存、写参与记录和 `seckill:reservation:{requestId}=PENDING`。
- [ ] 实现 `POST /api/seckill/{activityId}` 立即返回 `requestId` 与 `QUEUING`，以及结果查询接口返回 `QUEUING`、`SUCCESS:orderNo` 或 `FAILED`。
- [ ] 定义并实现 `reserveSeckillStock()` 与状态 `STOCK_RESERVED`；严禁借用 Token 额度冻结名称或实现。
- [ ] Redis 不可用时秒杀快速失败，不允许降级为直接写 MySQL。

**进入条件**

- 阶段 1 验收，Redis 与活动预热执行入口已可用；活动创建/结束操作的权限或初始化方式已决定。

**验收**

- [ ] 高并发请求下，Lua 保证成功预占数不超过 `seckill:stock:{id}` 初始量；Redis 库存不为负。
- [ ] 同一用户同一活动只能有一个成功预占；即使后续订单取消或超时，`seckill:users:{id}` 仍保留该用户。
- [ ] 未开始、已结束、售罄、重复参与分别返回约定业务结果，且不改变库存或参与集合。
- [ ] 每个成功请求都具有唯一 `requestId` 和 `PENDING` 预占记录；失败请求没有残留预占。
- [ ] Redis 故障注入时接口快速失败，未观测到 MySQL 秒杀库存回源写入。

**阶段产物**

- 已预热的秒杀活动、可审查 Lua 脚本、异步结果协议及并发资格校验测试。

## 阶段 3：RabbitMQ 异步下单、发放与可靠事件

**完成任务**

- [ ] 在决定 exchange、routing key、绑定、确认与失败策略后，声明 `seckill.order.queue`、`token.grant.queue` 和为延迟取消预留的队列拓扑；将决定写入配置/文档。
- [ ] 从 Lua 预占到发送秒杀消息时推进 `PENDING → SENT`；消息包含 `requestId, userId, activityId, packageId`。
- [ ] 秒杀消费者仅在预占仍为 `PENDING/SENT` 时，幂等创建 `WAIT_PAY` 的 `SECKILL` 订单；成功后推进至 `ORDER_CREATED` 并写 `SUCCESS:orderNo`，失败写 `FAILED`。
- [ ] 以 `orders(request_id)`、`orders(user_id, seckill_activity_id)` 及预占状态共同防重；消费者不得在获得资格时写 Token Ledger。
- [ ] 将阶段 1 的 Token 发放改由 `token.grant.queue` 消费，保留一次且仅一次到账语义。
- [ ] 实现 `local_message` 轮询/重试：事务内保存事件，提交后发送；发送失败或消费侧故障可恢复，且记录重试次数与下次时间。

**进入条件**

- 阶段 2 已通过，RabbitMQ 连通；MQ 拓扑和消息投递确认策略已获得阶段决策。

**验收**

- [ ] 秒杀请求先返回 `QUEUING`，消费者随后产生一个 `WAIT_PAY` 订单与 `SUCCESS:orderNo`；未支付前钱包和 Ledger 均不变化。
- [ ] 重复/乱序秒杀消息只产生一张订单；重复发放消息只产生一条到账流水。
- [ ] 模拟发布失败、消费者失败后重启，`local_message` 能重新投递并最终完成副作用，且没有重复记账。
- [ ] 已从 `PENDING/SENT` 释放为 `RELEASED` 的预占收到迟到消息时，消费者拒绝创建订单。
- [ ] 应用重启后未完成事件可被扫描恢复；测试报告包含数据库状态、消息状态与最终账本一致性证据。

**阶段产物**

- 可恢复的 RabbitMQ 异步秒杀下单与 Token 发放链路、消息幂等测试、已文档化的实际 MQ 拓扑。

## 阶段 4：支付超时、库存补偿与 Elasticsearch

**完成任务**

- [ ] 按已确定的 RabbitMQ TTL + DLX 拓扑实现 15 分钟支付超时检查；只允许 `WAIT_PAY(101) → TIMEOUT_CANCELLED(103)`。
- [ ] 为普通订单写入 `NORMAL_ORDER_STOCK_RELEASE(orderNo)`，事件处理器幂等回补 MySQL 普通库存。
- [ ] 为秒杀订单写入 `SECKILL_STOCK_RELEASE(orderNo)`，处理器通过 Lua 和 `release:{orderNo}` 标记幂等回补 Redis 秒杀库存，但不删除活动参与记录。
- [ ] 支付、用户取消、超时取消全部采用条件更新；已支付订单的延迟消息不得取消订单或回补库存。
- [ ] 建立 `token_package_index`，字段仅为 `id,name,modelCode,tokenAmount,price,description,scenarios`；实现套餐变更的索引同步策略。
- [ ] 实现关键词全文搜索、模型精确过滤、价格范围和 Token 数量范围；仍保留 MySQL 详情为事实源。

**进入条件**

- 阶段 3 已验收；RabbitMQ 延迟拓扑、ES 版本、mapping、分词器和索引同步策略已被明确决定。

**验收**

- [ ] 未支付订单在到期后只从 `101` 变为 `103`，并最终只回补一次对应库存。
- [ ] 已支付、已取消、已超时订单收到任意重复延迟消息后状态、库存和账本均不再改变。
- [ ] 秒杀超时释放后 Redis 库存正确回补，但同用户再次请求仍因参与记录被拒绝。
- [ ] 索引中的字段和来源套餐一致；关键词、模型、价格、Token 范围可以组合过滤并返回正确分页。
- [ ] 删除/变更套餐后的索引同步可观测且可重试；搜索异常不会篡改 MySQL 套餐数据。

**阶段产物**

- 超时关闭与库存补偿链路、ES 索引与套餐搜索 API、竞争/迟到消息回归测试。

## 阶段 5：API Key、模型调用与 Token 计费

**完成任务**

- [ ] 实现 API Key 创建、列表、吊销：只保存 `key_prefix` 与 SHA-256 `key_hash`，完整 Key 仅在创建响应中出现一次；实现 `apikey:{hash}` 鉴权缓存。
- [ ] 为 Key 配置允许的 `model_code` 列表，并实现 API Key 固定 QPS 限流；返回定义的业务错误，不能透传上游 HTTP 错误。
- [ ] 定义单一 `ModelProvider` 接口并接入一个 OpenAI-Compatible Provider；不接多 Provider、不支持 SSE。
- [ ] 实现 `/v1/chat/completions`：校验 Bearer Key、`Idempotency-Key`、模型权限、输入/输出限制，并维护 `model_request` 的 `PROCESSING/SUCCESS/FAILED/BILLING_UNKNOWN`。
- [ ] 用 Redis Lua 计算 `estimatedInputTokens + maxOutputTokens + safetyMargin`，原子检查可用额度与 `credit_limit=5000` 并冻结；使用 `token:reservation:{requestId}` 记录 `FROZEN`。
- [ ] 对成功且有真实 `usage.total_tokens` 的请求结算：按真实用量扣减、释放其余冻结、写唯一 `CONSUME` Ledger、标记 `SETTLED/SUCCESS`；真实用量超过冻结时允许到 `-5000`，再超过仍记录真实消费并将账户置为 `SUSPENDED`。
- [ ] 仅对网络错误、连接/读取超时与 `429/502/503/504` 重试，最多三次，退避 200ms、500ms；所有其他错误不重试。
- [ ] 最终失败释放冻结并标记 `FAILED`；上游成功但没有 usage 时标记 `BILLING_UNKNOWN`、成本 `UNKNOWN`、释放冻结且不扣用户；实现超时 `FROZEN` 预占的定时释放。

**进入条件**

- 阶段 1 的钱包和账本已验收；Provider 地址与凭据的安全注入方式、token 预估方式、最大输入长度、结果引用存储方式已决定。

**验收**

- [ ] 创建响应后，数据库中不含完整 API Key；正确 Key 可调用、吊销后立即拒绝，错误 Key 返回 `INVALID_API_KEY`。
- [ ] 同一 `Idempotency-Key`：`SUCCESS` 直接返回已有结果/引用、`PROCESSING` 返回处理中、`FAILED` 要求新 Key；任何路径都不会二次调用 Provider 或生成第二条 `CONSUME`。
- [ ] 初始 1,000,000、冻结 2,500 后可用为 997,500；真实使用 1,300 结算后可用为 998,700、冻结归零，且只存在一条消费流水。
- [ ] 可重试错误恰好最多三次，其他错误零次额外调用；最终失败完整释放冻结且不创建消费流水。
- [ ] 缺少 usage 返回 `BILLING_UNKNOWN`，平台成本为 `UNKNOWN`，用户余额未扣；过期 `FROZEN` 可被任务安全释放，`SETTLED` 不会被释放。
- [ ] 限流、模型权限、信用额度和账户 `SUSPENDED` 分别返回指定业务码；上游 `429/500` 不被原样暴露。

**阶段产物**

- API Key 生命周期、单 Provider 非流式调用、冻结/结算/失败恢复与幂等计费测试。

## 阶段 6：AI 导购、压测与交付文档

**完成任务**

- [ ] 实现 `POST /ai/recommend`：提取用途、预算、用量，调用 `searchPackages(...)` 获取 ES 候选，再调用 `estimateTokenUsage(...)` 和 LLM 生成基于候选的推荐理由。
- [ ] 限制 LLM 的输入只包含用户需求与 ES 候选；不得新增 Agent、向量库、额外知识库或复杂推荐算法。
- [ ] 编写 JMeter 场景：普通购买、秒杀资格预占/异步下单、模型计费（以 Provider mock 或受控环境执行）。
- [ ] 记录实际运行环境、数据量、并发、吞吐、p95/p99（如采集）、错误率、数据库写入量和观察窗口；对比直接 MySQL 与 Redis + Lua + MQ，但不把设想的 5k/1w 并发或 10 万请求写成已验证结论。
- [ ] 更新 `README.md`：架构图、启动步骤、依赖服务、数据初始化、接口示例、五条链路、状态机、压测方法/原始结果、已知限制和未实现范围。

**进入条件**

- 阶段 4 的 ES 检索及阶段 5 的模型调用已验收；导购需求提取格式和可接受的模型调用成本已明确。

**验收**

- [ ] 导购只从 ES 返回候选中推荐；推荐同时说明用途、预算/用量假设与套餐理由，检索无候选时给出明确无匹配结果。
- [ ] `searchPackages(...)` 和 `estimateTokenUsage(...)` 各有独立、可复现的测试输入输出；导购链路不依赖未列入 V1 的基础设施。
- [ ] JMeter 脚本可复跑，报告包含真实环境和指标；异常、失败或无法达到目标的结果如实保留。
- [ ] README 可使新开发者从空环境启动依赖、初始化数据、执行核心演示和定位测试/压测入口。

**阶段产物**

- ES 驱动的 AI 导购、可复现压测资产与结果、可交接 README。

## 最终验收门禁

只有下列全部通过，才可宣布 TokenHub V1 完成：

- [ ] 普通购买：创建订单、模拟支付、异步发放、Wallet 入账、Ledger 入账完整且幂等。
- [ ] 秒杀购买：Redis + Lua 不超卖、一人一单；异步订单成功；取消/超时后库存回补但用户不可重抢；迟到消息不能建单。
- [ ] 状态与事件：支付/取消/超时竞争只产生合法状态；每个副作用事件可由 `local_message` 恢复；重复消息不重复记账。
- [ ] 模型计费：Key 鉴权、权限、限流、冻结、真实 usage 结算、失败释放、`BILLING_UNKNOWN` 和幂等重试均符合约定。
- [ ] 搜索与导购：ES 关键词/多条件过滤准确；导购仅使用 ES 候选给出推荐。
- [ ] 交付：构建与关键自动化测试通过，初始化与演示文档可复现，压测报告记录事实而非目标设想。

## 每阶段收尾模板

```markdown
### 阶段 N 交付记录

- 已改动：`<文件或模块>` — `<目的>`
- 验证：`<命令/场景>` — `<通过、失败或未运行及原因>`
- 数据一致性证据：`<库存/订单/钱包/账本/消息的核对结果>`
- 待决策：`<仅列出阻塞下一阶段的未定项；没有则写“无”>`
```

## 计划自检

- 覆盖性：阶段 0–6 覆盖了 `AGENT.md` 的七日顺序、五条业务链路、数据/缓存/MQ 约束及最终横向验证。
- 边界：未纳入退款、流式调用、复杂架构或未定义的基础设施；MQ、ES、DTO 与 JWT 未定项保留在所属阶段的进入条件中。
- 可验收性：每个阶段均定义进入条件、可观察产物和覆盖正确性/幂等/竞争条件的验收项；未将性能设想作为通过标准。
