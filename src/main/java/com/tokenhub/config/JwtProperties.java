package com.tokenhub.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "tokenhub.jwt")
public record JwtProperties(String secret, long accessTokenTtlSeconds) { }
