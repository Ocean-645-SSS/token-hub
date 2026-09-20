package com.tokenhub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** A small, explicit input contract so usage assumptions are auditable. */
public record AiRecommendRequest(
        @NotBlank String requirement,
        BigDecimal budget,
        @Positive Integer dailyRequests,
        @Positive Long inputTokensPerRequest,
        @Positive Long outputTokensPerRequest,
        @Positive Integer days) { }
