# 阶段 3 交付记录

## RabbitMQ 拓扑决定

- Exchange：持久化 direct exchange `tokenhub.events`。
- 路由：`seckill.order` → `seckill.order.queue`，`token.grant` → `token.grant.queue`；队列均为持久化队列。
- 发布与失败：应用使用 publisher confirms/returns 配置；`local_message` 以 `PENDING(0)`、`SENT(1)`、`FAILED(2)` 管理投递。调度器扫描待发或到期重试事件；发送异常会增加重试次数并将下次重试时间延后 5 秒。消费者抛出异常时由 RabbitMQ 重新入队。
- 延迟取消：阶段 4 再以 TTL + DLX 声明，当前不启用。

## 交付内容

- 已改动：`RabbitMqConfig`、`LocalMessagePublisher`、`TokenGrantConsumer`——声明消息拓扑、投递本地事件并消费 Token 发放事件。支付端只创建 `TOKEN_GRANT` outbox 事件，不再直接发放。
- 已改动：`SeckillService`、`SeckillOrderConsumer`、`SeckillOrderService`——Lua 预占成功后创建 `SECKILL_ORDER` outbox 事件；投递后将预占从 `PENDING` 推进为 `SENT`。消费者只接受 `PENDING/SENT`，利用 `orders(request_id)`、活动维度唯一键和预占状态幂等创建 `SECKILL/WAIT_PAY` 订单，随后写入 `ORDER_CREATED` 与 `SUCCESS:orderNo`。
- 已改动：订单和本地消息 Mapper、AMQP 与应用配置——补齐秒杀订单插入、按请求号查重、本地事件扫描与重试查询。
- 验证：`mvn clean test`——通过，9 个测试全部通过。
- 数据一致性证据：秒杀消费者不调用钱包或账本 Mapper；重复的 `requestId` 会读取已有订单；Token 发放仍由 `TOKEN_GRANT_ORDER_<orderNo>` 的唯一账本标识保证一次且仅一次到账。
- 尚未运行：真实 RabbitMQ、Redis、MySQL 联合的端到端投递/重启恢复与乱序消息测试；未做压测。
- 待决策：无。
