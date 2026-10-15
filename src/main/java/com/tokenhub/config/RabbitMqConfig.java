package com.tokenhub.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration public class RabbitMqConfig {
    public static final String EXCHANGE = "tokenhub.events", DEAD_EXCHANGE = "tokenhub.dead";
    public static final String SECKILL_QUEUE = "seckill.order.queue", GRANT_QUEUE = "token.grant.queue", DELAY_QUEUE = "order.delay.queue", TIMEOUT_QUEUE = "order.timeout.queue", RELEASE_QUEUE = "stock.release.queue";
    public static final String PACKAGE_INDEX_QUEUE = "package.index.queue", PACKAGE_INDEX_DLQ = "package.index.dlq";
    public static final String PACKAGE_INDEX_FAILED = "package.index.failed";
    @Bean DirectExchange tokenhubEventsExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean DirectExchange tokenhubDeadExchange() { return new DirectExchange(DEAD_EXCHANGE, true, false); }
    @Bean Queue seckillOrderQueue() { return QueueBuilder.durable(SECKILL_QUEUE).build(); }
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
