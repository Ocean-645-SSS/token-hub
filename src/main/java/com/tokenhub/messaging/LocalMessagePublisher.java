package com.tokenhub.messaging;

import com.tokenhub.config.RabbitMqConfig;
import com.tokenhub.entity.LocalMessage;
import com.tokenhub.mapper.LocalMessageMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

@Component public class LocalMessagePublisher {
    private final LocalMessageMapper messages; private final RabbitTemplate rabbit; private final StringRedisTemplate redis;
    public LocalMessagePublisher(LocalMessageMapper messages, RabbitTemplate rabbit, StringRedisTemplate redis) { this.messages = messages; this.rabbit = rabbit; this.redis = redis; }
    @Scheduled(fixedDelayString = "${tokenhub.messaging.retry-delay-ms:5000}") public void publishPending() { messages.findPending(100).forEach(this::publish); }
    public void publish(LocalMessage message) {
        try {
            CorrelationData correlation = new CorrelationData(message.getMessageId());
            rabbit.convertAndSend(RabbitMqConfig.EXCHANGE, message.getTopic(), message.getPayload(), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) throw new IllegalStateException("broker rejected message: " + confirm.getReason());
            if ("SECKILL_ORDER".equals(message.getEventType())) redis.opsForHash().put("seckill:reservation:" + message.getBizId(), "status", "SENT");
            if (!"stock.release".equals(message.getTopic())) messages.markSentById(message.getId());
        } catch (Exception exception) { messages.recordRetry(message.getId(), 5); }
    }
}
