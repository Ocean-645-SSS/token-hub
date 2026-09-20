package com.tokenhub.messaging;
import com.tokenhub.config.RabbitMqConfig;
import com.tokenhub.service.OrderService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
@Component public class OrderTimeoutConsumer { private final OrderService service; public OrderTimeoutConsumer(OrderService service) { this.service = service; } @RabbitListener(queues = RabbitMqConfig.TIMEOUT_QUEUE) public void consume(String orderNo) { service.timeout(orderNo); } }
