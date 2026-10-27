package com.tokenhub.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration public class RabbitMqConfig {
    public static final String EXCHANGE = "tokenhub.events", DEAD_EXCHANGE = "tokenhub.dead";
    public static final String SECKILL_QUEUE = "seckill.order.queue", GRANT_QUEUE = "token.grant.queue", DELAY_QUEUE = "order.delay.queue", TIMEOUT_QUEUE = "order.timeout.queue", RELEASE_QUEUE = "stock.release.queue";
    public static final String PACKAGE_INDEX_QUEUE = "package.index.queue", PACKAGE_INDEX_DLQ = "package.index.dlq";
    public static final String PACKAGE_INDEX_FAILED = "package.index.failed";
    public static final String SECKILL_DLQ = "seckill.order.dlq";
    public static final String SECKILL_FAILED = "seckill.order.failed";
    public static final String RESERVATION_RELEASE_QUEUE = "seckill.reservation.release.queue";
    public static final String INVENTORY_QUEUE = "seckill.inventory.queue";
    @Bean DirectExchange tokenhubEventsExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean DirectExchange tokenhubDeadExchange() { return new DirectExchange(DEAD_EXCHANGE, true, false); }
    @Bean Queue seckillOrderQueue() { return QueueBuilder.durable(SECKILL_QUEUE)
            .deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey(SECKILL_FAILED).build(); }
    @Bean Queue seckillOrderDeadLetterQueue() { return QueueBuilder.durable(SECKILL_DLQ).build(); }
    @Bean Queue reservationReleaseQueue() { return QueueBuilder.durable(RESERVATION_RELEASE_QUEUE).build(); }
    @Bean Queue inventoryQueue() { return QueueBuilder.durable(INVENTORY_QUEUE).build(); }
    @Bean Binding seckillDeadBinding() { return BindingBuilder.bind(seckillOrderDeadLetterQueue()).to(tokenhubDeadExchange()).with(SECKILL_FAILED); }
    @Bean Binding reservationReleaseBinding() { return BindingBuilder.bind(reservationReleaseQueue()).to(tokenhubEventsExchange()).with("seckill.reservation.release"); }
    @Bean Binding inventoryBinding() { return BindingBuilder.bind(inventoryQueue()).to(tokenhubEventsExchange()).with("seckill.inventory"); }
    @Bean Queue tokenGrantQueue() { return QueueBuilder.durable(GRANT_QUEUE).build(); }
    @Bean Queue orderDelayQueue() { return QueueBuilder.durable(DELAY_QUEUE).ttl(900000).deadLetterExchange(EXCHANGE).deadLetterRoutingKey("order.timeout").build(); }
    @Bean Queue orderTimeoutQueue() { return QueueBuilder.durable(TIMEOUT_QUEUE).build(); }
    @Bean Queue stockReleaseQueue() { return QueueBuilder.durable(RELEASE_QUEUE).build(); }
    @Bean Queue packageIndexQueue() { return QueueBuilder.durable(PACKAGE_INDEX_QUEUE).deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey(PACKAGE_INDEX_FAILED).build(); }
    @Bean Queue packageIndexDeadLetterQueue() { return QueueBuilder.durable(PACKAGE_INDEX_DLQ).build(); }
    @Bean Binding seckillOrderBinding() { return BindingBuilder.bind(seckillOrderQueue()).to(tokenhubEventsExchange()).with("seckill.order"); }
    @Bean Binding tokenGrantBinding() { return BindingBuilder.bind(tokenGrantQueue()).to(tokenhubEventsExchange()).with("token.grant"); }
    @Bean Binding orderDelayBinding() { return BindingBuilder.bind(orderDelayQueue()).to(tokenhubEventsExchange()).with("order.delay"); }
    @Bean Binding orderTimeoutBinding() { return BindingBuilder.bind(orderTimeoutQueue()).to(tokenhubEventsExchange()).with("order.timeout"); }
    @Bean Binding stockReleaseBinding() { return BindingBuilder.bind(stockReleaseQueue()).to(tokenhubEventsExchange()).with("stock.release"); }
    @Bean Binding packageIndexBinding() { return BindingBuilder.bind(packageIndexQueue()).to(tokenhubEventsExchange()).with("package.index"); }
    @Bean Binding packageIndexDeadLetterBinding() { return BindingBuilder.bind(packageIndexDeadLetterQueue()).to(tokenhubDeadExchange()).with(PACKAGE_INDEX_FAILED); }
}
