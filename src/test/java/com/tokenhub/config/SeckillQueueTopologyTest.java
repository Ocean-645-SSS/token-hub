package com.tokenhub.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeckillQueueTopologyTest {
    @Test void exhaustedOrdersRouteToDurableCompensationQueue() {
        var config = new RabbitMqConfig();
        var queue = config.seckillOrderQueue();
        assertEquals(RabbitMqConfig.DEAD_EXCHANGE, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(RabbitMqConfig.SECKILL_FAILED, queue.getArguments().get("x-dead-letter-routing-key"));
        assertTrue(config.seckillOrderDeadLetterQueue().isDurable());
        assertEquals(RabbitMqConfig.SECKILL_DLQ, config.seckillDeadBinding().getDestination());
        assertEquals(RabbitMqConfig.SECKILL_FAILED, config.seckillDeadBinding().getRoutingKey());
        assertEquals("seckill.reservation.release", config.reservationReleaseBinding().getRoutingKey());
        assertEquals("seckill.inventory", config.inventoryBinding().getRoutingKey());
    }
}
