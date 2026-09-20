package com.tokenhub.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data @ConfigurationProperties(prefix = "tokenhub.model") public class ModelProperties { private String baseUrl; private String apiKey; private int maxInputCharacters = 32000; private int maxOutputTokens = 4096; private int connectTimeoutMs = 3000; private int readTimeoutMs = 30000; private int maxAttempts = 3; }
