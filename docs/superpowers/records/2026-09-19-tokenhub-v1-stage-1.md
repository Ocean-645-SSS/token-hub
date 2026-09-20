# 阶段 1 交付记录

- 已改动：`OrderController`、`OrderService`、`OrderMapper`、订单 DTO 与实体——实现 `POST /api/orders`、`POST /api/orders/{id}/pay`、`POST /api/orders/{id}/cancel`、`GET /api/orders/{id}`；订单号格式为固定 32 字符的 `ORD` + 29 位 UUID 字符。
- 已改动：`WalletController`、`WalletService`、钱包/账本 Mapper 与 DTO——实现仅按 JWT 当前用户查询的 `GET /api/wallet` 和 `GET /api/wallet/ledger`。
- 已改动：`TokenGrantHandler`、`LocalMessageMapper`——支付和取消在与状态迁移相同的事务中分别创建 `TOKEN_GRANT` 与 `NORMAL_ORDER_STOCK_RELEASE`；本阶段控制器在事务提交后调用本地处理器模拟事件消费。发放按 `TOKEN_GRANT_ORDER_<orderNo>` 唯一账本标识幂等，状态按 `PAID → GRANTING → GRANTED → COMPLETED` 推进；取消库存回补按事件状态幂等。
- 验证：`mvn test`——通过，5 个测试全部通过。新增单元测试覆盖可售套餐下单、库存不足不建单、支付/取消的旧状态条件与事件写入、状态冲突不写事件；已有套餐接口测试仍通过。
- 数据一致性证据：下单 SQL 仅在 `status = 1 AND stock > 0` 时扣减一份库存；支付和取消 SQL 均包含 `status = WAIT_PAY` 条件并断言影响行数；账本使用数据库唯一键 `uk_ledger_biz_id` 和 `INSERT IGNORE`，只有插入成功时才更新钱包。
- 尚未运行：连接真实 MySQL 的端到端、并发和压测场景；本机未在本阶段启动数据库服务。RabbitMQ 投递与重试由阶段 3 接入，本阶段保留稳定的 `local_message` 事件语义。
- 待决策：无。
