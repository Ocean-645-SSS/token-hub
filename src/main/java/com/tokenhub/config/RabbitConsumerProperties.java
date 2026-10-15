package com.tokenhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tokenhub.rabbit.consumer")
public record RabbitConsumerProperties(int concurrency, int maxConcurrency, int prefetch) {
    public RabbitConsumerProperties {
        if (concurrency < 1 || maxConcurrency < concurrency || prefetch < 1) {
            throw new IllegalArgumentException("tokenhub.rabbit.consumer values are invalid");
        }
    }
}
