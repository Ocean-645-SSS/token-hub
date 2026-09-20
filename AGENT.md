# TokenHub 项目改造约定

## 1. 来源与执行边界

- 设计来源：[高并发方案头脑风暴](chatgpt-conversation://6aad5ed7-4c3c-83ee-a29c-48435bb03aa6)，已按分页阅读全部 11 轮聊天，包括最初设想、后续修订和最后的 V1 汇总。
- 当前用户要求：将现有 Java 后端改造为高并发 Token 购买平台，严格遵循该聊天设计，逐步构造细节。本文是后续实现的设计基线；未经用户明确要求，不在仅维护文档的阶段修改业务代码、依赖、数据库或部署配置。
- 文件名按本次要求使用 `AGENT.md`。
- 后续以用户最新明确决定为准；聊天中的后期收敛方案作为设计基线。早期发散建议不能自动升级为需求，助手建议与用户明确想法存在冲突时，保留差异并在对应阶段确认。
- 未定细节列入待决策项，不自行增加业务、表、接口、中间件或复杂框架。不把本文档中的目标写成已经实现的功能。
- 每次只推进用户当前指定的阶段；完成后说明改动、验证结果和仍未确定的细节。

## 2. 项目定位与范围

TokenHub：面向 AI 开发者的模型 Token 交易与智能选购平台。以一周可完成、能演示、能压测、能讲清楚的 Java 后端项目为目标。

核心模块：用户与 API Key、Token 套餐、普通订单、秒杀活动、Token Wallet、Token Ledger、模型 API 调用、Elasticsearch 搜索、AI 智能导购。

用户购买的是平台账户内、按模型区分的 Token 使用额度。购买增加 Wallet；创建平台 API Key 获得调用凭证，两者分开。上游厂商 Key 由平台持有，不向用户出售或暴露。不同模型的额度不混用，V1 不引入统一 Credits 换算。

技术方向：JDK 21 + Spring Boot 3.x 单体，Controller → Service → Mapper；MySQL、MyBatis/MyBatis-Plus、Redis + Lua、RabbitMQ、Elasticsearch、LLM API；Docker Compose 管理中间件，JMeter 压测。包名为 `com.tokenhub`。

不纳入 V1：Spring Cloud、Gateway、Nacos、Sentinel、Seata、微服务拆分、真实支付宝/微信支付、退款与退款审核、复杂会员与权限、多租户、多模型动态路由、Prometheus/Grafana、向量数据库、GraphRAG、多 Agent、复杂推荐算法。Nginx 不作为当前必需组件。

## 3. 现有代码与改造方向

以下为 2026-09-19 静态阅读结果，未运行应用，不能据此认定现有功能运行正常。

| 位置 | 当前事实 | 后续改造方向 |
|---|---|---|
| `pom.xml` | `com.hmdp:hm-dianping`，Spring Boot 2.3.12.RELEASE，Java 8，MyBatis-Plus 3.4.3，Redis、Redisson | 按目标升级单体骨架；当前尚无 RabbitMQ、ES、LLM 依赖 |
| `src/main/java/com/hmdp/HmDianPingApplication.java` | 黑马点评启动入口 | 转为 TokenHub 项目，包名和迁移方式后续确定 |
| `service/impl/UserServiceImpl.java`、`config/MvcConfig.java` | 手机验证码、Redis 登录令牌与拦截器 | 按聊天后期方案转为简单 JWT 登录；平台 API Key 单独设计 |
| `service/impl/VoucherOrderServiceImpl.java` | Redis Lua 预扣、Redis Stream 消费、Redisson 锁、MySQL 优惠券订单 | 借鉴库存与重复购买校验思路，改为活动维度 + RabbitMQ 异步订单 |
| `src/main/resources/seckill.lua` | 按 voucherId 扣库存、Set 防重复，并 XADD 到 `stream.orders` | 改成活动校验、库存、一人一单；消息链路按 RabbitMQ 方案重新设计 |
| `utils/CacheClient.java` | 空值缓存、互斥重建、逻辑过期工具 | 复用适合的基础能力，按 V1 缓存策略调整，不要求启用全部策略 |
| Shop、ShopType、Voucher、VoucherOrder 等实体及业务 | 商铺与优惠券模型 | 转向 Token 套餐、秒杀活动和订单模型，不能只改展示名称 |
| Blog、Follow、BlogComments、签到、上传等 | 点评项目周边功能 | 不纳入 TokenHub V1；具体清理随阶段执行 |
| `src/main/resources/db/hmdp.sql`、`application.yaml` | 原点评表结构和环境配置 | 后续另行确定 TokenHub 表结构、初始化及配置 |

Java 相对路径均以 `src/main/java/com/hmdp/` 为基准。现有缓存工具、ID 工具和分层结构只是复用候选，不代表已满足新业务的一致性、幂等和状态机要求。

## 4. 五条核心业务链路

### 普通购买

选择套餐 → MySQL 事务扣普通库存并创建 `WAIT_PAY` 订单 → 模拟支付 → `PAID` → RabbitMQ 发放 Token → Wallet 增加额度、Ledger 记录入账 → 订单完成。

普通下单使用 MySQL 事务，不走 Redis 秒杀库存。支付后的 Token 发放与秒杀订单共用链路。

### 秒杀购买

活动开始前预热独立秒杀库存 → 用户请求 → Redis Lua 原子检查活动、库存、一人一单 → 预扣一份商品库存并记录用户 → RabbitMQ → 消费者幂等创建 `WAIT_PAY` 订单 → 支付 → Token 发放 → Wallet / Ledger。

- `token_package.stock` 是普通库存，`seckill_activity.stock` 是本场单独划出的库存，不能共用。
- 请求立即返回 `requestId` 与 `QUEUING`；通过结果接口查询 `QUEUING`、`SUCCESS:orderNo`、`FAILED`。
- 秒杀消费者负责落订单；抢到资格或创建待支付订单时，不产生 Token 到账流水。
- 秒杀库存预占命名为 `STOCK_RESERVED` / `reserveSeckillStock()`；模型额度预冻结为 `TOKEN_QUOTA_RESERVED` / `reserveTokenQuota()`，不能混用。
- 15 分钟未支付通过 RabbitMQ TTL + DLX 检查并取消；秒杀订单只回补 Redis 库存，`seckill:users:{activityId}` 保留参与记录，不移除用户。已支付订单不执行超时取消。
- 同一用户同场活动只允许成功创建一个订单；即使取消或超时，仍不得再次抢购。Redis 的参与记录和库存占用不是同一概念。

### Wallet / Ledger 与状态机

- Wallet 按 `(user_id, model_code)` 维护可用额度、冻结额度、信用额度。
- Ledger 记录业务变更，以 `biz_id` 唯一索引保证幂等；同一业务重复投递不得重复加减额度。
- 支付、MQ 消费、Token 发放均需幂等。凡是状态变化后还需执行外部副作用，必须在同一个 MySQL 事务中提交状态更新与 `local_message` 事件；事件独立重试，副作用自身幂等。
- 状态更新携带原状态条件，并检查影响行数。`affectedRows == 1` 表示获得执行后续副作用的资格，不表示副作用已经完成。

```text
101 WAIT_PAY ──支付──→ 201 PAID → 301 GRANTING → 302 GRANTED → 401 COMPLETED
     ├──用户取消──→ 102 USER_CANCELLED（终态）
     └──超时取消──→ 103 TIMEOUT_CANCELLED（终态）
```

订单履约使用 Token 发放语义。V1 不实现退款，订单状态不包含 `501/502/503` 或独立退款单；早期物流式收货状态和 `303` 发放异常也不纳入当前主状态集合。

### 模型调用与计费

`POST /v1/chat/completions`，使用 `Authorization: Bearer <平台 API Key>`。

API Key 鉴权 → 校验 `Idempotency-Key` → 预估输入 Token + 最大输出 Token + 安全余量 → Redis Lua 原子检查额度并冻结 → 平台调用上游模型 → 获取真实 usage 后结算 → 释放冻结余额 → 写消费 Ledger → 返回回答。

- 冻结不等于消费，不能只检查余额后并发放行、调用结束再无保护地扣费。
- 聊天示例：初始 1,000,000，冻结 2,500 后可用 997,500、冻结 2,500；真实消耗 1,300 后可用 998,700、冻结归零。
- V1 固定 `credit_limit = 5_000` Token，不做会员等级。预冻结量为 `estimatedInputTokens + maxOutputTokens + safetyMargin`；冻结是并发保护，最终账务只认真实 `usage.total_tokens`。
- 若真实消耗不超过冻结量，按真实 usage 结算并释放剩余冻结。若真实消耗超过冻结量，再次检查补扣后余额不低于 `-credit_limit`；未突破则正常结算，突破则仍记录真实消费、账户转为 `SUSPENDED`，后续请求拒绝。
- 只有上游调用成功、返回 usage 且平台账务结算成功，才视为本次计费成功；不以客户端是否最后收到响应为准。客户端同一 `Idempotency-Key` 重试，若已成功则直接返回既有结果或结果引用，不重复调用、不重复扣费；`PROCESSING` 返回处理中，`FAILED` 必须使用新的 Key 发起新请求。
- 可重试失败仅限网络连接失败、连接或读取超时、HTTP `429`、`502`、`503`、`504`；最多三次总尝试，退避 200ms、500ms。参数、鉴权、权限、用户额度、模型名、内容等错误不重试。所有失败尝试不计入用户账单；最终失败释放全部冻结额度并标记 `FAILED`。
- 上游成功但缺少 usage 时，标记 `BILLING_UNKNOWN`，释放冻结额度、记录异常，暂不向用户扣费。平台成本仅在能获取 usage 时记为 `KNOWN`，否则记为 `UNKNOWN`，不得伪造用量。
- Redis 是调用期间的实时额度状态；MySQL Wallet 与 Ledger 是持久化账本。每次结算以 `requestId` 去重，同步落库失败可重试或由本地事件驱动最终落库。Redis 另存 `token:reservation:{requestId}`；超时仍为 `FROZEN` 的预占由定时任务释放并标记 `RELEASED`，`SETTLED` 的预占不得释放。
- API Key 数据库存前缀和 SHA-256 哈希，完整 Key 只在创建时展示一次；支持创建、列表、吊销，同一账户可有多个 Key。

### ES 搜索与 AI 导购

- V1 单一索引 `token_package_index`，来自套餐数据。
- 字段：`id`、`name`、`modelCode`、`tokenAmount`、`price`、`description`、`scenarios`。
- 支持关键词全文搜索、模型精确过滤、价格范围、Token 数量范围，导购按用途场景检索。
- AI 链路：自然语言需求 → 提取用途、预算、用量 → ES 检索候选套餐 → LLM 基于候选结果给出套餐与推荐理由。
- 后期收敛为两个工具：`searchPackages(...)`、`estimateTokenUsage(...)`。不增加复杂 Agent 或额外知识库索引。
- 用量估算示例：每日 100 次 × 每次输入 2,000 与输出 1,000 × 30 天 = 每月 9,000,000 Token。示例不代表实际套餐或模型价格。

## 5. MySQL 字段基线

下面记录 V1 的 **8 张核心业务表**及字段，以及为可靠事件投递新增的 `local_message` 基础设施表；尚不是可执行 DDL。

| 表 | 字段 |
|---|---|
| `user` | `id, username, password_hash, status, created_at, updated_at` |
| `token_package` | `id, name, model_code, token_amount, price, description, suitable_scenario, stock, status, created_at, updated_at` |
| `seckill_activity` | `id, package_id, seckill_price, stock, start_time, end_time, status, created_at, updated_at` |
| `orders` | `id, order_no, user_id, package_id, order_type, seckill_activity_id, request_id, original_price, pay_price, token_amount, status, created_at, paid_at, completed_at, expired_at, updated_at` |
| `token_wallet` | `id, user_id, model_code, available_token, frozen_token, credit_limit, version, created_at, updated_at` |
| `token_ledger` | `id, user_id, model_code, biz_id, type, amount, balance_before, balance_after, remark, created_at` |
| `api_key` | `id, user_id, name, key_prefix, key_hash, status, created_at, last_used_at` |
| `model_request` | `id, request_id, user_id, api_key_id, model_code, input_tokens, output_tokens, total_tokens, reserved_tokens, status, retry_count, provider_cost_status, result_reference, created_at, finished_at` |
| `local_message` | `id, message_id, event_type, biz_id, topic, payload, status, retry_count, next_retry_time, created_at, updated_at` |

明确的唯一约束：`token_wallet(user_id, model_code)`、`token_ledger(biz_id)`、`orders(order_no)`、`orders(request_id)`、`orders(user_id, seckill_activity_id)`、`model_request(request_id)`、`local_message(message_id)`、`local_message(event_type, biz_id)`。MySQL 的多个 `NULL` 不冲突，因此普通订单的 `seckill_activity_id = NULL` 不受活动维度唯一约束影响。其他约束和字段类型后续细化。

枚举基线：

- `orders.order_type`：`NORMAL`、`SECKILL`。
- `token_ledger.type`：`PURCHASE`、`SECKILL_PURCHASE`、`CONSUME`、`ADJUSTMENT`。V1 不包含 `REFUND`。
- `model_request.status`：`PROCESSING`、`SUCCESS`、`FAILED`、`BILLING_UNKNOWN`；`provider_cost_status`：`KNOWN`、`UNKNOWN`。`result_reference` 保存可复用的模型结果或其存储引用，供同一 `Idempotency-Key` 重试返回。
- 发放幂等标识示例：`TOKEN_GRANT_ORDER_<订单标识>`；消费流水按模型请求业务标识去重。

## 6. Redis 与 RabbitMQ 基线

| Redis Key | 类型 | 用途 |
|---|---|---|
| `package:{id}` | String / Hash | 套餐缓存 |
| `seckill:activity:{id}` | Hash | 活动信息 |
| `seckill:stock:{id}` | String | 活动库存 |
| `seckill:users:{id}` | Set | 参与过本场活动的用户；取消后仍保留，不能用于表示当前库存占用 |
| `seckill:result:{requestId}` | String | 异步结果 |
| `seckill:reservation:{requestId}` | Hash / String | 秒杀预占状态：`PENDING`、`SENT`、`ORDER_CREATED`、`RELEASED` |
| `wallet:{userId}:{model}` | Hash | Token 热余额 |
| `token:reservation:{requestId}` | Hash | 模型调用冻结记录及 `FROZEN`、`SETTLED`、`RELEASED` 状态 |
| `apikey:{hash}` | String / Hash | API Key 鉴权缓存 |

缓存治理：套餐参数校验与短 TTL 空值防穿透；普通套餐 TTL 加随机值防集中失效；活动前预热、活动期间核心 Key 不自然过期，结束后由业务控制清理；Redis 异常时秒杀快速失败，不将秒杀流量直接回源 MySQL。布隆过滤器、逻辑过期、Redis Cluster 不作为 V1 必做项。

| RabbitMQ 队列 | 职责 |
|---|---|
| `seckill.order.queue` | 异步创建秒杀订单，处理后写结果缓存 |
| `token.grant.queue` | 支付后幂等发放 Token、写 Ledger、推进发放状态 |
| `order.delay.queue` | 15 分钟支付超时检查，TTL + DLX |

秒杀消息字段：`requestId, userId, activityId, packageId`。秒杀预占仅允许 `PENDING → SENT`、`PENDING/SENT → ORDER_CREATED`、`PENDING/SENT → RELEASED`；一旦为 `RELEASED`，迟到消息不得创建订单。秒杀库存释放 Lua 通过 `release:{orderNo}` 幂等标记保证仅回补一次。Token 发放消息称为 `TokenGrantMessage`，其完整字段未定。延迟死信消费的 exchange、routing key、队列绑定也未定；不把“3 个 Queue”的概念性列表误当成完整可运行拓扑。

## 7. 接口基线

平台业务接口统一使用 `/api` 前缀与 `{ code, message, data }` 响应结构；`/v1/chat/completions` 单独保持 OpenAI-Compatible 响应结构，不套用该包装。下列路径据此整理。

```text
POST   /api/auth/login
GET    /api/packages
GET    /api/packages/{id}
GET    /api/packages/search
POST   /api/orders
POST   /api/orders/{id}/pay
POST   /api/orders/{id}/cancel
GET    /api/orders/{id}
POST   /api/seckill/{activityId}
GET    /api/seckill/result/{requestId}
GET    /api/wallet
GET    /api/wallet/ledger
POST   /api/api-keys
GET    /api/api-keys
DELETE /api/api-keys/{id}
POST   /v1/chat/completions
POST   /ai/recommend
```

Controller：`AuthController`、`PackageController`、`OrderController`、`SeckillController`、`WalletController`、`ApiKeyController`、`ChatController`、`AiGuideController`。不因现有项目有其他接口而将其自动纳入新项目。

## 8. 分阶段推进与验收

| 顺序 | 聊天中的目标 | 阶段结果 |
|---|---|---|
| Day 1 | 项目骨架 + MySQL | 用户、套餐、订单、Wallet、Ledger 表，普通套餐查询 |
| Day 2 | 普通购买 | 创建订单 → 模拟支付 → Token 到账 → Ledger |
| Day 3 | Redis + Lua 秒杀 | 库存预热、一人一单、原子预扣 |
| Day 4 | RabbitMQ | 异步秒杀下单、异步发放、幂等 |
| Day 5 | 超时订单 + ES | TTL/DLX 超时取消，套餐全文检索与过滤 |
| Day 6 | API Key + 模型计费 | 鉴权 → 冻结 → 模型调用 → 结算 → Ledger |
| Day 7 | AI 导购 + 压测 + README | 基于 ES 的推荐，JMeter 结果，架构图与项目总结 |

这是来源聊天的开发顺序，不代表一次性授权执行全部阶段。Day 2 与 Day 4 的发放接入衔接在对应阶段细化，不自行引入长期双实现。

最终验收五条链路：普通购买到账、秒杀异步下单到账、模型冻结结算、ES 搜索过滤、AI 检索推荐。横向验证不超卖、一人一单且取消后不能重抢、重复消息不重复记账、合法状态迁移、`local_message` 可恢复副作用、秒杀迟到消息不会在库存回补后创建订单、同一 `Idempotency-Key` 不重复调用模型，以及缓存策略。

压测记录实际环境、并发、吞吐、错误率、数据库写入量，并按聊天方向对比直接 MySQL 与 Redis + Lua + MQ；聊天里的 5k/1w 并发、10 万请求均为设想，不能当成已验证性能。

如时间不足，聊天的最低保留范围是普通购买 + Redis Lua 秒杀 + RabbitMQ + Wallet/Ledger + ES，其次模型调用，最后 AI 导购。是否裁剪由用户后续决定，不自动删减。

## 9. 已冻结的 V1 实施细节

1. **计费、重试与交付边界**：一项业务请求最多产生一条用户 `CONSUME` 流水。只有上游成功、取得 usage 且平台账务结算成功才扣用户 Token；不以客户端是否最终收到响应为判定条件。可重试失败只限网络错误、连接/读取超时和 HTTP `429/502/503/504`，总尝试最多三次，退避 200ms、500ms。失败尝试的上游成本不计入用户账单；可取得 usage 时标记平台成本 `KNOWN`，否则 `UNKNOWN`。不可重试错误包括参数、上游鉴权/权限、用户额度、模型名与内容参数错误。

2. **退款**：V1 完全不实现退款，不包含退款状态、`refund_order`、额度批次、消费归属或冻结归属。退款仅作为后续版本扩展，不可据此增加表、接口或账务逻辑。

3. **订单关联与幂等落库**：秒杀和普通订单共用 `orders` 表。秒杀订单持久化 `seckill_activity_id` 与 `request_id`，以支持结果查询、库存释放和消费幂等；普通订单对应字段为 `NULL`。

```sql
CREATE TABLE orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    order_no VARCHAR(32) NOT NULL,
    user_id BIGINT NOT NULL,
    package_id BIGINT NOT NULL,

    order_type TINYINT NOT NULL, -- NORMAL / SECKILL

    seckill_activity_id BIGINT NULL,
    request_id VARCHAR(64) NULL,

    token_amount BIGINT NOT NULL,

    original_price DECIMAL(10,2) NOT NULL,
    pay_price DECIMAL(10,2) NOT NULL,

    status INT NOT NULL,

    created_at DATETIME NOT NULL,
    paid_at DATETIME NULL,
    completed_at DATETIME NULL,
    expired_at DATETIME NULL,
    updated_at DATETIME NOT NULL,

    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_request_id (request_id),
    UNIQUE KEY uk_user_activity (
        user_id,
        seckill_activity_id
    ), -- 同一用户在同场秒杀只能有一个订单

    KEY idx_user_id (user_id),
    KEY idx_status (status)
);
```

| 标识 | 含义 | 产生时间 |
|---|---|---|
| `activityId` | 订单关联的秒杀活动 | 创建秒杀活动时 |
| `requestId` | 用户秒杀请求与异步订单的关联标识 | 用户点击秒杀时 |
| `orderNo` | 对外业务订单标识 | MQ Consumer 创建订单时 |
| `orders.id` | 数据库内部主键 | 插入订单时 |

4. **事件与一致性**：对数据库状态变更后的异步事件，状态更新和 `local_message` 插入必须在同一个 MySQL 事务中提交；消息任务独立重试，消费端以 `requestId`、`bizId` 和业务条件保证幂等。秒杀的 Redis 预扣不能纳入该事务，Lua 负责记录预占状态，定时重投与库存补偿实现最终一致性。

普通订单超时示例：事务内执行 `101 → 103`，同时插入 `NORMAL_ORDER_STOCK_RELEASE(orderNo)` 事件；提交后独立恢复 MySQL 库存。秒杀超时类似地插入 `SECKILL_STOCK_RELEASE(orderNo)`，事件处理器以 Redis Lua 幂等恢复库存。状态变更成功只表示获得副作用执行权；消息未完成时仍由 `local_message` 重试。

`local_message`：

```sql
CREATE TABLE local_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    message_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    biz_id VARCHAR(64) NOT NULL,

    topic VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,

    status TINYINT NOT NULL, -- 0 PENDING, 1 SENT, 2 FAILED
    retry_count INT NOT NULL DEFAULT 0,

    next_retry_time DATETIME NULL,

    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,

    UNIQUE KEY uk_message_id(message_id),
    UNIQUE KEY uk_event_biz(event_type, biz_id),

    KEY idx_status_retry(status, next_retry_time)
);
```

5. **取消、支付与迟到消息竞争**：订单取消、支付、超时关闭都必须使用带旧状态条件的 SQL 更新，只有 `affectedRows == 1` 才能写入对应的待执行事件。秒杀预占状态只允许 `PENDING → SENT`、`PENDING/SENT → ORDER_CREATED`、`PENDING/SENT → RELEASED`；`RELEASED` 后的迟到 MQ 消息不得创建订单。
库存恢复规则：

| 场景 | 状态更新 | 成功后的事件或副作用 |
|---|---|---|
| 普通订单用户取消 | `101 → 102` | 本地事件恢复 MySQL 普通库存 |
| 普通订单超时取消 | `101 → 103` | 本地事件恢复 MySQL 普通库存 |
| 秒杀订单用户取消 | `101 → 102` | 本地事件调用 Redis Lua 回补秒杀库存，保留参与记录 |
| 秒杀订单超时取消 | `101 → 103` | 本地事件调用 Redis Lua 回补秒杀库存，保留参与记录 |
| 支付成功 | `101 → 201` | 不恢复库存，写入 Token 发放事件 |

6. **调用参数与模型**：以下为 V1 固定边界。

信用额度：统一 `5_000` Token，不做会员分级。

Tokenizer：不自己写复杂 tokenizer。请求前预估可使用简单估算或供应商 SDK；最终扣费只认上游真实 usage，冻结量额外包含可配置 `safetyMargin`。
上游供应商：V1 只接 1 个 OpenAI-Compatible Provider，代码抽象一个 ModelProvider 接口，方便以后扩展，但不真的接多家。
输入/输出限制：固定最大输入长度、最大输出 Token，例如 maxOutputTokens <= 4096；具体数值放配置文件，不写死到业务代码。
流式：V1 不支持 SSE 流式调用，只支持一次性响应。这样冻结、结算、失败重试最简单。
超时：连接超时、响应超时放配置，例如 connectTimeout=3s、readTimeout=30s。数字只是 V1 默认值，不作为业务承诺。
限流：先做 API Key 维度固定 QPS，例如 5 QPS，Redis 滑动窗口/令牌桶任选一种简单实现；不要做动态套餐限流。
API Key 权限：V1 一个 Key 可以配置允许访问的 model_code 列表；如果不想做 UI，数据库字段或关联表即可。
错误码：使用平台业务码，不直接把上游 `429/500` 原样暴露给用户。

配置基线：

```yaml
tokenhub:
  billing:
    credit-limit: 5000
    safety-margin: 512

  model:
    max-output-tokens: 4096
    connect-timeout-ms: 3000
    read-timeout-ms: 30000
    max-attempts: 3

  rate-limit:
    api-key-qps: 5
```

错误码：
| HTTP | 业务码                             | 含义         |
| ---- | ------------------------------- | ---------- |
| 401  | `INVALID_API_KEY`               | Key 无效     |
| 403  | `MODEL_NOT_ALLOWED`             | Key 无模型权限  |
| 402  | `CREDIT_LIMIT_EXCEEDED`         | 超过信用额度     |
| 429  | `RATE_LIMIT_EXCEEDED`           | API Key 超限 |
| 400  | `INVALID_REQUEST`               | 参数错误       |
| 404  | `PACKAGE_NOT_FOUND`             | 套餐不存在      |
| 409  | `ORDER_STATE_CONFLICT`          | 订单状态冲突     |
| 409  | `SECKILL_DUPLICATE`             | 已参加本场秒杀    |
| 410  | `SECKILL_ENDED`                 | 活动已结束      |
| 503  | `MODEL_TEMPORARILY_UNAVAILABLE` | 上游重试后仍失败   |
| 500  | `TOKEN_SETTLEMENT_FAILED`       | Token 结算异常 |
| 502  | `BILLING_UNKNOWN`               | 上游成功但缺少 usage，未扣费 |

7. **基础设施与接口基线**：
JDK：21
Spring Boot：3.x
包名：com.tokenhub
API 前缀：统一 /api
模型调用接口单独保留 OpenAI 风格：/v1/chat/completions
JWT：登录成功签发 access token，先不做 refresh token
用户初始化：先提供 SQL 初始化一个测试用户，注册功能可选
分页：统一 page / size
响应结构：`/api/**` 统一 `code / message / data`；`/v1/chat/completions` 保持 OpenAI-Compatible 结构

还需在实现相应阶段细化：MQ exchange/routing key/绑定、ES mapping/分词和同步方式，以及各接口请求体、分页响应和 JWT claim。

## 10. 协作与验证

面向用户默认简体中文，标识保持英文。先说明结果和影响，必要时补充证据。每次修改前核对本文与用户最新决定；设计有歧义时先完成不依赖该决定的工作，再在对应阶段讨论。

运行与改动相称的检查，重点验证库存、账务、幂等和状态迁移；不为纯文档或低影响可逆改动新增测试。未运行、未实现、未压测的内容如实标明。完成后清理本次无用临时文件。
