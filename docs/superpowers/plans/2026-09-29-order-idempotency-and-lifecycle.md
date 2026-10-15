# Order Idempotency and Lifecycle Implementation Plan

**Goal:** 普通购买同键仅创建一个订单；秒杀取消回补正确库存；秒杀订单进入现有超时队列。
**Architecture:** 非事务入口处理同键重放，独立事务服务插入订单、扣库存和写 Outbox；数据库唯一约束处理并发。取消只写按类型分流的事件，MQ 处理库存回补。
**Tech Stack:** Java 17, Spring Boot, MyBatis, MySQL, Redis Lua, RabbitMQ.

- [x] 增加 purchase_key 与用户维度唯一约束及可重复执行迁移，Mapper 支持查询和插入。
- [x] 普通购买接口要求 Idempotency-Key；事务外捕获重复键并查询原订单，校验套餐一致。
- [x] 取消按订单类型创建事件，移除 Controller 同步普通库存回补；普通回补直接调用事务代理方法。
- [x] 秒杀建单事务同时写 ORDER_TIMEOUT，重复消息不再写事件。
- [x] 测试重放、参数冲突、并发重复键、库存失败、取消分流、重复超时与秒杀建单事件；运行 Maven 测试。
- [x] 更新 README 和 JMeter 请求，记录验证结果。

验证：`mvn test -q` 通过，{'tests': 54, 'failures': 0, 'errors': 0, 'skipped': 0}。JMeter XML 解析与 git diff --check 通过。Docker daemon 未运行，未执行真实 MySQL/Redis/RabbitMQ 联调或数据库迁移。
