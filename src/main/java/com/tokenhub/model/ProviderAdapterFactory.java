package com.tokenhub.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.config.ModelProperties;

/** Builds the right vendor adapter for a configured provider type. */
public final class ProviderAdapterFactory {

    private ProviderAdapterFactory() { }

    public static ModelProvider create(ModelProperties.Provider p, int connectTimeoutMs, int readTimeoutMs, ObjectMapper json) {
        String type = p.getType() == null || p.getType().isBlank() ? "openai" : p.getType().trim().toLowerCase();
        return switch (type) {
            case "anthropic" -> new AnthropicProvider(p.getCode(), p.getBaseUrl(), p.getApiKey(), connectTimeoutMs, readTimeoutMs, json);
            // OpenAI-compatible vendors (OpenAI, DeepSeek, vLLM, ...) share one adapter.
            default -> new OpenAiCompatibleModelProvider(p.getCode(), p.getBaseUrl(), p.getApiKey(), connectTimeoutMs, readTimeoutMs, json);
        };
    }
}
