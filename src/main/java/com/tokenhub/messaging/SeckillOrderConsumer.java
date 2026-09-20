package com.tokenhub.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.config.RabbitMqConfig;
import com.tokenhub.service.SeckillOrderService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component public class SeckillOrderConsumer {
    private final ObjectMapper json; private final SeckillOrderService service;
    public SeckillOrderConsumer(ObjectMapper json, SeckillOrderService service) { this.json = json; this.service = service; }
    @RabbitListener(queues = RabbitMqConfig.SECKILL_QUEUE) public void consume(String payload) throws Exception { service.create(json.readValue(payload, SeckillOrderMessage.class)); }
}
