package com.tokenhub.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.config.RabbitMqConfig;
import com.tokenhub.service.SeckillInventoryProjection;
import com.tokenhub.service.SeckillReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class SeckillRecoveryConsumer {
    private static final Logger log = LoggerFactory.getLogger(SeckillRecoveryConsumer.class);
    private final ObjectMapper json;
    private final SeckillReservationService recovery;
    private final SeckillInventoryProjection projection;

    public SeckillRecoveryConsumer(ObjectMapper json, SeckillReservationService recovery, SeckillInventoryProjection projection) {
        this.json = json; this.recovery = recovery; this.projection = projection;
    }

    @RabbitListener(queues = RabbitMqConfig.SECKILL_DLQ)
    public void failed(String payload) {
        SeckillOrderMessage event;
        try {
            event = json.readValue(payload, SeckillOrderMessage.class);
            if (event.requestId() == null || event.requestId().isBlank()) throw new IllegalArgumentException("missing requestId");
        } catch (Exception exception) {
            log.error("invalid seckill dead-letter; overdue reservation scan remains the fallback", exception);
            throw new AmqpRejectAndDontRequeueException("invalid seckill dead-letter", exception);
        }
        // Let transient DB failures requeue; ACK only after the compensation transaction commits.
        recovery.compensate(event.requestId());
    }

    @RabbitListener(queues = RabbitMqConfig.RESERVATION_RELEASE_QUEUE)
    public void release(String requestId) { recovery.releaseReservation(requestId); }

    @RabbitListener(queues = RabbitMqConfig.INVENTORY_QUEUE)
    public void synchronize(String activityId) {
        long id;
        try { id = Long.parseLong(activityId); }
        catch (NumberFormatException exception) { throw new AmqpRejectAndDontRequeueException("invalid activity id", exception); }
        projection.synchronize(id);
    }
}
