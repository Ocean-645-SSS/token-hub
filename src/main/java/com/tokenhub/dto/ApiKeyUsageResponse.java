package com.tokenhub.dto;

public record ApiKeyUsageResponse(Long apiKeyId, Long requestCount, Long settledTokens) { }
