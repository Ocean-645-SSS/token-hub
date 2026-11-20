package com.tokenhub.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.dto.ChatCompletionRequest;
import org.springframework.http.HttpHeaders;

import java.util.*;

/** One OpenAI-compatible upstream endpoint (OpenAI, DeepSeek, vLLM, ...). */
public class OpenAiCompatibleModelProvider extends HttpModelProvider {

    public OpenAiCompatibleModelProvider(String code, String baseUrl, String apiKey,
                                         int connectTimeoutMs, int readTimeoutMs, ObjectMapper json) {
        super(code, baseUrl, apiKey, connectTimeoutMs, readTimeoutMs, json);
    }

    @Override protected String path() { return "/v1/chat/completions"; }

    @Override protected void applyHeaders(HttpHeaders headers) {
        if (!apiKey().isBlank()) headers.setBearerAuth(apiKey());
    }

    @Override protected Map<String, Object> buildBody(ChatCompletionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("messages", request.messages());
        payload.put("max_tokens", request.maxTokens());
        if (request.tools() != null) payload.put("tools", request.tools());
        return payload;
    }

    @Override protected ProviderResponse parse(String raw) throws Exception {
        Map<String, Object> result = json().readValue(raw, new TypeReference<>() { });
        Map<String, Object> usage = result.get("usage") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return new ProviderResponse(result, number(usage.get("prompt_tokens")), number(usage.get("completion_tokens")), number(usage.get("total_tokens")));
    }

}
