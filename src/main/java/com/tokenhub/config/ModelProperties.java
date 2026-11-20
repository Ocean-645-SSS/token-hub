package com.tokenhub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data @ConfigurationProperties(prefix = "tokenhub.model")
public class ModelProperties {
    private String baseUrl;
    private String apiKey;
    private String guideModel = "gpt-4o-mini";
    private int maxInputCharacters = 32000;
    private int maxOutputTokens = 4096;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 30000;
    private int maxAttempts = 3;
    private List<Provider> providers = new ArrayList<>();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Routing routing = new Routing();

    /** One upstream provider candidate for routing. */
    @Data public static class Provider {
        private String code;
        private String type = "openai";
        private String baseUrl;
        private String apiKey;
        private double unitCost;
    }

    @Data public static class CircuitBreaker {
        private int failureThreshold = 3;
        private long cooldownMs = 10000;
    }

    @Data public static class Routing {
        private double costWeight = 1.0;
        private double latencyWeight = 1.0;
    }
}
