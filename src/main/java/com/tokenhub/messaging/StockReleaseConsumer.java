package com.tokenhub.messaging;
import com.tokenhub.config.RabbitMqConfig;
import com.tokenhub.service.StockReleaseService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
@Component public class StockReleaseConsumer { private final StockReleaseService service; public StockReleaseConsumer(StockReleaseService service){this.service=service;} @RabbitListener(queues=RabbitMqConfig.RELEASE_QUEUE) public void consume(String orderNo){service.release(orderNo);} }
