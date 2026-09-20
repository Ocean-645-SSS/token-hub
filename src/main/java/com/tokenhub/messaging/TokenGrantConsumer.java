package com.tokenhub.messaging;

import com.tokenhub.config.RabbitMqConfig;
import com.tokenhub.service.TokenGrantHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component public class TokenGrantConsumer {
    private final TokenGrantHandler handler;
    public TokenGrantConsumer(TokenGrantHandler handler) { this.handler = handler; }
    @RabbitListener(queues = RabbitMqConfig.GRANT_QUEUE) public void consume(String orderNo) { handler.handle(orderNo); }
}
