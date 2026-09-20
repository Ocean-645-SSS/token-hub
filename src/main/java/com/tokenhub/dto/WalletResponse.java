package com.tokenhub.dto;

public record WalletResponse(String modelCode, Long availableToken, Long frozenToken, Long creditLimit) { }
