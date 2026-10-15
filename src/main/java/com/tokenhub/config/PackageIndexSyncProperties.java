package com.tokenhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tokenhub.package-index-sync")
public record PackageIndexSyncProperties(int maxAttempts, long retryBackoffMs, long reconciliationIntervalMs) {
    public PackageIndexSyncProperties {
        if (maxAttempts < 1 || retryBackoffMs < 0 || reconciliationIntervalMs < 1000) {
            throw new IllegalArgumentException("tokenhub.package-index-sync values are invalid");
        }
    }
}
