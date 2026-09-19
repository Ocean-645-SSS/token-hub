package com.tokenhub.dto;
public record LoginResponse(String accessToken, String tokenType, long expiresIn) { }
