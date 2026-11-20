package com.tokenhub.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.dto.ChatCompletionRequest;
import org.springframework.http.HttpHeaders;

import java.util.*;

/**
 * Anthropic Messages API adapter. Translates the unified OpenAI-shaped request/response:
 * splits the system prompt out of messages, and normalizes usage to totalTokens
 * (input_tokens + output_tokens, since Anthropic has no total_tokens field).
 */
public class AnthropicProvider extends HttpModelProvider {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    public AnthropicProvider(String code, String baseUrl, String apiKey,
                             int connectTimeoutMs, int readTimeoutMs, ObjectMapper json) {
        super(code, baseUrl, apiKey, connectTimeoutMs, readTimeoutMs, json);
    }

    @Override protected String path() { return "/v1/messages"; }

    @Override protected void applyHeaders(HttpHeaders headers) {
        headers.set("x-api-key", apiKey());
        headers.set("anthropic-version", ANTHROPIC_VERSION);
    }

    @Override protected Map<String, Object> buildBody(ChatCompletionRequest request) {
        return toVendorBody(request);
    }

    @Override protected ProviderResponse parse(String raw) throws Exception {
        Map<String, Object> vendor = json().readValue(raw, new TypeReference<>() { });
        return fromVendorBody(vendor);
    }

    @Override protected boolean isRetryable(int status) {
        return status == 429 || status == 529 || status >= 500;
    }

    /** Pure translation: unified messages -> Anthropic body (system split out, max_tokens required). */
    static Map<String, Object> toVendorBody(ChatCompletionRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        String system = null;
        for (Map<String, Object> m : request.messages()) {
            String role = String.valueOf(m.get("role"));
            String content = String.valueOf(m.get("content"));
            if ("system".equals(role)) {
                system = content;
            } else {
                messages.add(Map.of("role", role, "content", content));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        body.put("messages", messages);
        if (system != null) body.put("system", system);
        return body;
    }

    /** Pure translation: Anthropic body -> unified OpenAI-shaped ProviderResponse. */
    @SuppressWarnings("unchecked")
    static ProviderResponse fromVendorBody(Map<String, Object> vendor) {
        String text = "";
        Object content = vendor.get("content");
        if (content instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && "text".equals(m.get("type")) && m.get("text") != null) {
                    text = String.valueOf(m.get("text"));
                    break;
                }
            }
        }
        Map<String, Object> usage = vendor.get("usage") instanceof Map<?, ?> u ? (Map<String, Object>) u : Map.of();
        Long input = number(usage.get("input_tokens"));
        Long output = number(usage.get("output_tokens"));
        Long total = (input != null && output != null) ? input + output : null;
        Map<String, Object> body = Map.of("choices", List.of(
                Map.of("message", Map.of("role", "assistant", "content", text))));
        return new ProviderResponse(body, input, output, total);
    }
}
