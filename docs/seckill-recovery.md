# 秒杀失败补偿与库存恢复（2026-10-27）

## 修改后的行为

- Redis Lua 保留快速校验、预扣与参与标记；只有 MySQL 预占、库存账本扣减和建单 Outbox **同事务提交**后，接口才返回 `QUEUING`。
- `seckill_activity.stock` 继续表示活动配置的总库存。新增 `seckill_inventory.available_stock` 是最终可用库存；Redis 的库存只承担入口估计，不能作为最终库存依据。
- `seckill_reservation` 以 `request_id` 唯一标识一次占用，并保留 `user_id + activity_id` 唯一约束。Redis 参与集合丢失也不能重复参与。
- 建单与补偿都先锁相同预占行。建单、订单超时 Outbox、`ORDER_CREATED` 状态同事务提交；补偿先提交 `COMPENSATION_PENDING` 后，所有迟到建单消息停止建单。
- 建单临时失败最多尝试 3 次（200ms、400ms 退避），随后拒绝且不重新入原队列，进入专用 DLQ。DLQ 消费者持久化补偿任务；明确的套餐无效也进入同一补偿状态。
- 失败预占释放、取消订单释放都在 MySQL 事务中将预占改为 `RELEASED`、账本加一、写库存同步 Outbox。取消订单还会同时标记原释放事件完成。回滚则全部回滚；重复消息看见终态不会再加一。
- Redis 同步读取最新账本，通过 Lua 原子写入“绝对可用量 + 版本”，拒绝较旧版本。重放不会执行 `INCR`。相同版本允许覆盖，以修复只有 Redis 预扣、数据库事务未提交时的低估。
- 每 30 秒扫描超过 300 秒未建单的预占、待释放预占，并修复尚未结束活动的 Redis 元数据和库存。两项周期可通过 `tokenhub.seckill.recovery-interval-ms`、`tokenhub.seckill.reservation-timeout-seconds` 配置。
- 查询秒杀结果以 MySQL 为准，Redis 丢失结果键不会让已接受的请求消失。已建单后取消仍可查到原订单号。

```text
RESERVED ──建单事务──> ORDER_CREATED ──取消后释放事务──> RELEASED
    │
    └──补偿事务──> COMPENSATION_PENDING ──释放事务──> RELEASED
```

## 一致性边界与代价

Redis 快照写回时可能仍有尚未提交的预扣，因此缓存短暂高估或低估库存都是允许的。**所有放行请求必须再经过 MySQL 的 `available_stock > 0` 条件扣减。** 数据库拒绝的请求不会建单；后续快照修复入口估计。不能绕过该数据库校验，也不能再从活动总库存直接覆盖 Redis。

每次接受预占增加一次数据库事务和一次库存快照事件，热点活动的账本更新会串行竞争；优先保证当前项目的恢复正确性，原有仅 Redis 预扣的性能结果不再代表本实现。更高吞吐需要另行设计分片库存或配额，不应通过取消最终库存校验获得。

## 已有环境升级

1. 停止新秒杀入口，并停止所有旧版本消费者、发布器、定时任务。先备份数据库；旧版与新版不可混跑。
2. 在 `tokenhub` 数据库执行 `src/main/resources/db/migrations/20261027_seckill_recovery.sql`。它回填已有订单、尚未建单的持久化 Outbox，并据此计算可用库存。已完成回补的旧取消订单标记为 `RELEASED`。脚本可重复执行，不会重置已有账本余额；历史占用超过总库存会失败，需先核查历史数据。
3. 对已有 `seckill.order.queue` 做拓扑迁移：新声明增加了 DLX 参数，RabbitMQ 不允许直接改已有队列的声明参数。先将积压消息可靠转存到临时持久队列并确认旧队列为空，再删除空旧队列；由新程序重建，随后把转存消息可靠转发回来。禁止直接删除非空队列。无积压时可删除空队列后重建。
4. 启动新版本，确认 `seckill.order.dlq`、`seckill.reservation.release.queue`、`seckill.inventory.queue` 及绑定创建成功。首次预热前，清理本次迁移涉及活动的旧 `seckill:stock:version:{activityId}`（首次升级该键通常不存在），再调用预热，核对 Redis 可用量等于账本余额。这里清理的是缓存版本，不是业务预占记录。
5. 检查 `available_stock >= 0`，核对每个活动的“可用量 + 未释放预占数 = 活动配置总库存”，再恢复入口。迁移前只有 Redis 预扣、没有任何持久化事件的请求不算已接受请求，重建时释放其缓存占用。

数据库初始化脚本 `tokenhub.sql` 已包含同一迁移，新的数据库无需另行回填。已有环境建议只执行专用迁移，避免重跑初始化中的种子数据。

## 验证

运行 `mvn test`。新增 SQL 集成测试使用 H2 MySQL 模式及真实 Spring 事务/MyBatis 映射，覆盖：预占与 Outbox 回滚、两种竞争提交顺序、业务失败补偿、回补 Outbox 写失败回滚、重复取消释放、最后一份库存竞争。另验证死信拓扑、实际三次重试、恢复消费者错误传播、Redis 丢失后的结果查询。LuaJ 执行真实 Lua 文件（用 GET/SET 适配器模拟 Redis 命令），验证重复、乱序、缓存丢失、孤立预扣恢复；它不是 Redis 服务端联调。

H2 不能替代 MySQL InnoDB、RabbitMQ 和 Redis 的完整联调。上线前仍需执行上述迁移和拓扑迁移，并复测故障重启及吞吐。
