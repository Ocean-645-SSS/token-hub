# 阶段 0 交付记录

- 已改动：`pom.xml`、`src/main/java/com/tokenhub/**`、`src/main/resources/application.yaml`、`src/main/resources/db/tokenhub.sql`——建立 JDK 17 / Spring Boot 3.3 的 TokenHub 骨架；经用户确认，已删除全部旧 `com.hmdp` 源码、测试与资源，仅保留 TokenHub。
- 已改动：认证、统一响应、异常处理、请求日志、套餐查询——`POST /api/auth/login` 签发 JWT access token；`GET /api/packages`、`GET /api/packages/{id}`、`GET /api/packages/search` 使用统一 `{ code, message, data }` 包装；不存在套餐返回 `PACKAGE_NOT_FOUND`。
- 已改动：SQL 与枚举——9 张 V1 表、规定的唯一索引、测试用户和两个套餐演示数据；订单、账本、模型请求及成本状态均使用冻结的 V1 枚举，不含退款项。
- 基线事实：分支 `master`，开始时 HEAD `a129c68147419d7d64f0c18fec7e9f021e07026f`；原项目为 Boot 2.3.12、Java 8，原测试未在改造前运行。TCP 检查显示 MySQL `127.0.0.1:3306` 可达，旧 Redis `192.168.150.101:6379` 不可达。
- 验证：`mvn -Dmaven.repo.local=.m2 test -q`——通过；覆盖 `PACKAGE_NOT_FOUND` 的 HTTP 404 与统一响应包装。`mvn -Dmaven.repo.local=.m2 spring-boot:run -q`——通过；启动日志显示 `TokenHubApplication` 在 8080 监听且 readiness 为 `ACCEPTING_TRAFFIC`。以本机 MySQL `root` 账户并指定客户端字符集 `utf8mb4` 执行 `tokenhub.sql` 成功：`information_schema` 核验 9 张表、`demo` 用户以及 `orders` 的三项唯一索引。HTTP 联调结果：套餐列表 200 且含 2 条记录，不存在套餐 404/`PACKAGE_NOT_FOUND`，无 token 访问 `/api/auth/me` 为 401，`demo/password` 登录和携带 JWT 访问 `/api/auth/me` 均为 200。
- 数据一致性证据：`orders` 的 `uk_user_activity(user_id, seckill_activity_id)` 已由 MySQL `SHOW INDEX` 核验，并进行了临时写入验证：同一用户的两笔 `NORMAL` 订单均可保存 `seckill_activity_id = NULL`；同一用户、同一活动的第二笔 `SECKILL` 订单被 MySQL 以 `Duplicate entry '9901-9901'` 拒绝。验证记录已删除，查询确认残留数为 0；阶段 0 未产生业务订单、库存、钱包、账本或消息数据。
- 待决策：无。
