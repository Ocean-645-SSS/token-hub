package com.tokenhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "tokenhub.admin")
public record AdminProperties(String userIds) {
    public Set<Long> allowedUserIds() {
        if (userIds == null || userIds.isBlank()) return Set.of();
        try {
            return Arrays.stream(userIds.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Long::valueOf)
                    .filter(id -> id > 0)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("tokenhub.admin.user-ids must contain comma-separated numeric user IDs", exception);
        }
    }
}
