package com.tokenhub.model;

import com.tokenhub.dto.ChatCompletionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicProviderTest {

    @Test void splitsSystemPromptOutOfMessages() {
        ChatCompletionRequest req = new ChatCompletionRequest(
                "claude-3-5-sonnet",
                List.of(
                        Map.of("role", "system", "content", "You are helpful"),
                        Map.of("role", "user", "content", "hi")),
                512, false);

        Map<String, Object> body = AnthropicProvider.toVendorBody(req);

        assertEquals("claude-3-5-sonnet", body.get("model"));
        assertEquals(512, body.get("max_tokens"));
        assertEquals("You are helpful", body.get("system"));
        List<?> messages = (List<?>) body.get("messages");
        assertEquals(1, messages.size());
        assertEquals(Map.of("role", "user", "content", "hi"), messages.get(0));
    }

    @Test void normalizesUsageToTotalTokens() {
        Map<String, Object> vendor = Map.of(
                "content", List.of(Map.of("type", "text", "text", "hello world")),
                "usage", Map.of("input_tokens", 10, "output_tokens", 5));

        ModelProvider.ProviderResponse resp = AnthropicProvider.fromVendorBody(vendor);

        assertEquals(10L, resp.promptTokens());
        assertEquals(5L, resp.completionTokens());
        assertEquals(15L, resp.totalTokens());
        // output is OpenAI-shaped so the client always sees the same contract
        List<?> choices = (List<?>) resp.body().get("choices");
        Object message = ((Map<?, ?>) choices.get(0)).get("message");
        assertEquals("hello world", ((Map<?, ?>) message).get("content"));
    }

    @Test void missingUsageLeavesTotalNullForBillingUnknown() {
        Map<String, Object> vendor = Map.of("content", List.of(Map.of("type", "text", "text", "x")));

        ModelProvider.ProviderResponse resp = AnthropicProvider.fromVendorBody(vendor);

        assertNull(resp.totalTokens());
    }
}
