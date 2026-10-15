package com.tokenhub.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.config.RabbitMqConfig;
import com.tokenhub.service.SeckillOrderService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import com.tokenhub.profiling.SeckillTrace;

@Component public class SeckillOrderConsumer {
    private final ObjectMapper json; private final SeckillOrderService service;
    public SeckillOrderConsumer(ObjectMapper json, SeckillOrderService service) { this.json = json; this.service = service; }
    @RabbitListener(queues = RabbitMqConfig.SECKILL_QUEUE, containerFactory = "seckillRabbitListenerContainerFactory") public void consume(String payload) throws Exception { long deliveryStartNanos = System.nanoTime(); SeckillOrderMessage message=json.readValue(payload, SeckillOrderMessage.class); SeckillTrace.markAt(message.requestId(), "m3", deliveryStartNanos); SeckillTrace.mark(message.requestId(), "m4"); SeckillTrace.mark(message.requestId(), "t8"); service.create(message); SeckillTrace.mark(message.requestId(), "m5"); }
}
