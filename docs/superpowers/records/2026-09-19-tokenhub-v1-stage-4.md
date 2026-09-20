# 阶段 4 交付记录

- 已改动：`RabbitMqConfig`、订单超时与库存补偿消费者、`release_seckill_stock.lua`——用 TTL + DLX 在 15 分钟后检查 `WAIT_PAY` 订单；仅成功执行 `101 -> 103` 时创建库存回补事件。普通订单回补 MySQL 库存，秒杀订单用 `release:{orderNo}` Lua 幂等键回补 Redis 秒杀库存，且不删除参与记录。
- 已改动：`PackageSearchService`、`PackageService`、`PackageController`、`TokenPackageMapper` 与应用配置——建立 `token_package_index`，仅索引 `id,name,modelCode,tokenAmount,price,description,scenarios`；应用启动时从 MySQL 全量同步有效套餐，`GET /api/packages/search` 改由 ES 提供关键词全文检索、模型精确筛选、价格区间和 Token 数量区间组合分页。套餐详情仍从 MySQL 读取。
- 同步策略：当前没有套餐创建、编辑或删除接口，因此采用“应用启动时全量同步有效套餐”。新增管理接口时，应在其 MySQL 事务提交后补充按套餐 ID 的索引更新或删除事件。
- 验证：`mvn clean test` 通过，9 个测试全部成功；已连接本地 Elasticsearch 8.19.21，成功创建并读取 `token_package_index` mapping，字段类型与约定一致。Redis、RabbitMQ、MySQL 与 ES 均连通时，应用成功启动并将 2 条有效套餐同步到 ES；组合搜索请求返回唯一匹配套餐。
- 运行期修复：`SeckillService` 的生产构造器显式标记为 Spring 注入入口；ES `_refresh` 请求改为无 body，修复了启动同步失败。
- 尚未运行：尚未人为等待 15 分钟或注入延迟消息，故超时取消与普通/秒杀库存回补的端到端验证仍未执行。
- 待决策：无。
