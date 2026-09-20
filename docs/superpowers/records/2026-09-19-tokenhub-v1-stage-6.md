# 阶段 6 交付记录

- 已改动：`AiGuideController`、`AiGuideService` 及 AI 导购 DTO——新增 `POST /ai/recommend`；用量估算使用 `dailyRequests × (inputTokensPerRequest + outputTokensPerRequest) × days`，再以用途和预算调用 `searchPackages(...)`。LLM 输入严格由用户需求、估算假设和 ES 候选的索引字段组成；无候选时不调用 LLM 并返回明确结果。
- 已改动：`docs/jmeter/tokenhub-v1.jmx` 与运行说明——提供普通购买、秒杀预占/异步结果、受控 Provider mock 计费的可复跑 JMeter 场景。
- 已改动：`README.md`——补充架构图、依赖启动、初始化、接口示例、五条链路、状态机、压测入口与 V1 限制。
- 验证：`mvn test` — 通过（20 tests）；覆盖独立用量估算、`searchPackages(...)` 输入输出、仅基于 ES 候选的推荐、无候选时不调用模型，以及既有订单/秒杀/账务回归。
- 压测：未运行。缺少可连接的 MySQL、Redis、RabbitMQ、Elasticsearch 与受控 Provider mock 组合环境；`docs/jmeter/results/2026-09-19-stage-6.md` 如实保留未采集状态，未作性能结论。
- 待决策：无。
