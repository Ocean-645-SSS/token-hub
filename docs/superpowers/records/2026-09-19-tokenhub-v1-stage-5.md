# 阶段 5 交付记录

- 已改动：ApiKeyController、ApiKeyService、ApiKeyMapper 与 API Key DTO/实体——支持创建、列表、吊销；仅持久化 SHA-256 哈希与前缀，创建响应中的完整 Key 只返回一次。apikey:{hash} 缓存用于后续鉴权，吊销时立即失效。
- 已改动：ChatController、ChatCompletionService、ModelProvider、OpenAiCompatibleModelProvider——增加单一非流式 OpenAI-Compatible /v1/chat/completions 调用、Bearer Key、模型白名单、固定 QPS、输入/输出上限与 Idempotency-Key 状态处理。
- 已改动：TokenReservationService、FrozenReservationCleanupJob、请求/钱包/账本 Mapper——Redis Lua 原子冻结、真实 usage 结算、唯一 CONSUME 流水、失败释放、BILLING_UNKNOWN 与过期 FROZEN 清理；超出信用额度后暂停账户。
- 已改动：application.yaml、tokenhub.sql——外置 Provider 凭据与超时、额度/安全余量、限流配置；API Key 模型白名单和模型结果引用列。
- 验证：mvn test -q — 通过。覆盖现有订单/秒杀回归与 API Key 不存储明文的单元测试。
- 数据一致性证据：冻结镜像到 token_wallet.available_token/frozen_token；结算以 request_id 的唯一模型请求和 MODEL_CONSUME_<requestId> 的唯一账本标识去重；重复成功请求读取持久化结果，不再次调用 Provider 或扣款。
- 待决策：无。
