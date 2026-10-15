# Outbox 第一轮优化报告

## 结论

本轮没有达到“几百毫秒级”目标，但可靠性保持正确：最终验证 100/100 订单落库，outbox `published=100 / confirmed=100 / failed=0`，RabbitMQ `ready=0 / unacked=0`。

| 指标 | 原始报告 | 同口径调度基线 | 优化后最终样本 |
|---|---:|---:|---:|
| E2E Avg | 5587.64 ms | 7991.91 ms | 6709.43 ms |
| E2E P50 | 5919 ms | 8030 ms | 6959 ms |
| E2E P95 | 6826 ms | 9451 ms | 8040 ms |
| E2E P99 | 6912 ms | 9553 ms | 8148 ms |
| E2E Max | — | 9553 ms | 8164 ms |

“同口径调度基线”和优化样本均使用修正后的客户端预热逻辑；原始报告的 6.912 s 使用的是旧客户端，因此不能直接作为严格 A/B 百分比。相对本次同口径基线，P99 下降约 14.7%，但仍高于原始报告值，不能宣称整体性能已优于原系统。

## 实际修改

- `LocalMessagePublisher`：增加 `SENDING` claim、200 ms 可配置轮询、500 条批量、有界 in-flight、异步 publisher confirm、过期 claim 回收和统计日志。
- Redis reservation 的 `SENT` 更新移到 confirm 成功回调，并用 Lua 条件更新，避免覆盖 `ORDER_CREATED`。
- `MessagingProperties`：暴露轮询间隔、batch、in-flight、claim 超时和重试间隔。
- `LocalMessageMapper`：增加 claim、confirm 成功、失败重试和 stale claim 回收 SQL。
- `local_message` 新库 DDL 增加 `idx_message_publish(status,next_retry_time,id)`；本次本机 A/B **未应用该 ALTER**，因为未获授权修改现有表结构。

## 为什么没有完全消除延迟

日志证明优化后消息确认本身成功且队列清空，但 100 单 E2E 仍是秒级。当前剩余瓶颈可能在应用调度线程、MySQL outbox 插入/扫描、结果接口轮询以及同机客户端资源竞争；本轮没有采集足够的 GC、Hikari 等待、MySQL slow query 和 Redis command latency，因此不能把剩余延迟归因给单一组件。

## 正确性

- Redis remaining stock：0（初始 100）。
- MySQL orders / distinct users / distinct requestIds：100 / 100 / 100。
- duplicate user orders：0；duplicate requestId orders：0。
- outbox pending/failed：0；RabbitMQ ready/unacked：0。
- overselling：0；successful reservation without final order：0。

## 测试限制

本轮完成的是 100 单 E2E 和可靠性核验；500/1000 单 E2E 以及完整 1/10/50/100/200/300/500 HTTP A/B 尚未在本轮完成，不能虚构其结果。下一轮应先把客户端、应用和中间件拆机或补充 Micrometer/数据库慢查询观测，再继续定位秒级尾延迟。
