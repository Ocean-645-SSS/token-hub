package com.tokenhub.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.config.PackageIndexSyncProperties;
import com.tokenhub.config.RabbitMqConfig;
import com.tokenhub.service.PackageSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PackageIndexConsumer {
    private static final Logger log = LoggerFactory.getLogger(PackageIndexConsumer.class);

    private final ObjectMapper json;
    private final PackageSearchService search;
    private final PackageIndexSyncProperties properties;

    public PackageIndexConsumer(ObjectMapper json, PackageSearchService search, PackageIndexSyncProperties properties) {
        this.json = json;
        this.search = search;
        this.properties = properties;
    }

    @RabbitListener(queues = RabbitMqConfig.PACKAGE_INDEX_QUEUE)
    public void consume(Message message) {
        String messageId = message.getMessageProperties().getMessageId();
        PackageIndexSyncMessage event;
        try {
            event = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8), PackageIndexSyncMessage.class);
            if (event.packageId() == null || event.packageId() <= 0) {
                throw new IllegalArgumentException("packageId must be a positive integer");
            }
        } catch (Exception exception) {
            log.error("rejecting invalid package-index event messageId={}", messageId, exception);
            throw new AmqpRejectAndDontRequeueException("invalid package-index event", exception);
        }

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                search.synchronizePackage(event.packageId());
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (attempt < properties.maxAttempts()) {
                    log.warn("package-index sync failed messageId={} packageId={} attempt={}/{}; retrying",
                            messageId, event.packageId(), attempt, properties.maxAttempts(), exception);
                    try {
                        Thread.sleep(properties.retryBackoffMs());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AmqpRejectAndDontRequeueException("package-index retry interrupted", interrupted);
                    }
                }
            }
        }
        log.error("package-index sync exhausted messageId={} packageId={} attempts={}; routing to DLQ",
                messageId, event.packageId(), properties.maxAttempts(), lastFailure);
        throw new AmqpRejectAndDontRequeueException("package-index sync retries exhausted", lastFailure);
    }
}
