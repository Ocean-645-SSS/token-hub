# 阶段 2 交付记录

- 已改动：`pom.xml`、`application.yaml`——加入 Spring Data Redis（Lettuce）及 `TOKENHUB_REDIS_HOST`、`TOKENHUB_REDIS_PORT`、`TOKENHUB_REDIS_TIMEOUT` 外部化配置。
- 已改动：`SeckillService`、`SeckillController`、`SeckillActivityMapper`——实现 `POST /api/seckill/{activityId}` 与 `GET /api/seckill/result/{requestId}`。资格请求只执行 Redis Lua，不在请求路径读取 MySQL；Redis 不可用或活动未预热时返回 `SECKILL_UNAVAILABLE`。
- 已改动：`lua/reserve_seckill_stock.lua`——原子检查活动状态和起止时间、活动库存与用户参与集合；成功时扣减 `seckill:stock:{id}`、写入 `seckill:users:{id}` 和无自然过期的 `seckill:reservation:{requestId}=PENDING`。普通 `token_package.stock` 没有被秒杀路径使用。
- 已改动：`tokenhub.sql`——提供一个最小演示秒杀活动初始化数据。预热入口为 `SeckillService.preheat(activityId)`，须在活动开始前由运营初始化流程调用；不在应用重启时自动重置活动库存。
- 验证：`mvn test`——通过，9 个测试全部通过。新增测试覆盖 Lua 成功结果、售罄/重复参与错误映射、Redis 故障快速失败且不访问 MySQL，以及无效活动拒绝预热。
- 数据一致性证据：Lua 脚本仅在资格检查全部通过后执行 `DECR`、`SADD` 和 `HSET`；任一失败码在修改 Redis 状态之前返回。用户参与集合与库存 Key 独立，后续取消或超时不会允许用户再次参与。
- 尚未运行：真实 Redis 上的 Lua 并发压测和 Redis 故障注入；阶段 3 的 RabbitMQ 投递、`PENDING → SENT` 及异步建单尚未实现。
- 待决策：无。
