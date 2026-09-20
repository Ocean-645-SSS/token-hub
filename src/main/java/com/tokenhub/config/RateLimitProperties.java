package com.tokenhub.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data @ConfigurationProperties(prefix = "tokenhub.rate-limit") public class RateLimitProperties { private int apiKeyQps = 5; }
