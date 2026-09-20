package com.tokenhub.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration public class RabbitMqConfig {
    public static final String EXCHANGE = "tokenhub.events", SECKILL_QUEUE = "seckill.order.queue", GRANT_QUEUE = "token.grant.queue", DELAY_QUEUE = "order.delay.queue", TIMEOUT_QUEUE = "order.timeout.queue", RELEASE_QUEUE = "stock.release.queue";
    @Bean DirectExchange tokenhubEventsExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean Queue seckillOrderQueue() { return QueueBuilder.durable(SECKILL_QUEUE).build(); }
    @Bean Queue tokenGrantQueue() { return QueueBuilder.durable(GRANT_QUEUE).build(); }
    @Bean Queue orderDelayQueue() { return QueueBuilder.durable(DELAY_QUEUE).ttl(900000).deadLetterExchange(EXCHANGE).deadLetterRoutingKey("order.timeout").build(); }
    @Bean Queue orderTimeoutQueue() { return QueueBuilder.durable(TIMEOUT_QUEUE).build(); }
    @Bean Queue stockReleaseQueue() { return QueueBuilder.durable(RELEASE_QUEUE).build(); }
    @Bean Binding seckillOrderBinding() { return BindingBuilder.bind(seckillOrderQueue()).to(tokenhubEventsExchange()).with("seckill.order"); }
    @Bean Binding tokenGrantBinding() { return BindingBuilder.bind(tokenGrantQueue()).to(tokenhubEventsExchange()).with("token.grant"); }
    @Bean Binding orderDelayBinding() { return BindingBuilder.bind(orderDelayQueue()).to(tokenhubEventsExchange()).with("order.delay"); }
    @Bean Binding orderTimeoutBinding() { return BindingBuilder.bind(orderTimeoutQueue()).to(tokenhubEventsExchange()).with("order.timeout"); }
    @Bean Binding stockReleaseBinding() { return BindingBuilder.bind(stockReleaseQueue()).to(tokenhubEventsExchange()).with("stock.release"); }
}
