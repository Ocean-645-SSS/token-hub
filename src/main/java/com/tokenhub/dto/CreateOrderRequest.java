package com.tokenhub.dto;

import jakarta.validation.constraints.NotNull;

public record CreateOrderRequest(@NotNull Long packageId) { }
