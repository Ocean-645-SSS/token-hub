package com.tokenhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tokenhub.messaging")
public record MessagingProperties(long pollIntervalMs, int batchSize, int maxInFlight, int claimTimeoutSeconds,
                                  int retryDelaySeconds, int publisherCoreThreads, int publisherMaxThreads,
                                  int publisherQueueCapacity) {
    public MessagingProperties {
        if (pollIntervalMs < 10 || batchSize < 1 || maxInFlight < 1 || claimTimeoutSeconds < 1 || retryDelaySeconds < 1
                || publisherCoreThreads < 1 || publisherMaxThreads < 1 || publisherQueueCapacity < 1
                || publisherCoreThreads > publisherMaxThreads) {
            throw new IllegalArgumentException("tokenhub.messaging values are invalid; pollIntervalMs must be at least 10 and publisher core threads must not exceed max threads");
        }
    }
}
