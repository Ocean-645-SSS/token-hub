package com.tokenhub.dto;
import java.time.LocalDateTime;
import java.util.List;
public record ApiKeyResponse(Long id, String name, String keyPrefix, List<String> allowedModels, Integer status, LocalDateTime createdAt, LocalDateTime lastUsedAt, String key) { }
