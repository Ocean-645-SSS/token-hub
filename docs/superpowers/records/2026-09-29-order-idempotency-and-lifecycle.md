# 订单幂等与生命周期修复

- 普通下单要求 Idempotency-Key，purchase_key 使用 ASCII 二进制比较并按 user_id 唯一；同键返回原订单，参数冲突拒绝，新键支持复购。非事务入口调用 OrderService 事务代理，重复键回滚后查询成功订单。
- 插入订单、条件扣库存、超时 Outbox 同事务提交；插入订单先抢占唯一键，重复请求不扣库存。
- 主动取消按订单类型创建普通或秒杀回补事件；Controller 不再同步调用普通回补。普通回补调用事务代理；秒杀 Lua 成功后才确认事件，失败保留待处理，可处理 status=2 的重试事件。
- 秒杀建单同时写 ORDER_TIMEOUT，进入现有 TTL + DLX；Redis 成功结果在数据库事务提交后发布。并发重复插入回滚后由 MQ 在新事务重试。
- 新增/扩展普通幂等、HTTP 必填头、取消分流、超时防重、秒杀 Outbox 失败与提交回调、库存回补失败测试。校正已有 Publisher 测试的 RabbitTemplate 五参数 mock。
- 新建独立可重复执行迁移 src/main/resources/db/migrations/2026-09-29-order-purchase-idempotency.sql；README 和 JMeter 更新请求头。

验证：`mvn test -q` 通过；统计：{'tests': 54, 'failures': 0, 'errors': 0, 'skipped': 0}。JMeter XML 可解析，diff 无空白错误。测试使用 mock 验证分支/副作用调用和事务同步回调，未验证真实 MySQL 并发或等待 15 分钟的 MQ 链路。Docker daemon 未运行；未执行数据库迁移、部署或性能压测。
