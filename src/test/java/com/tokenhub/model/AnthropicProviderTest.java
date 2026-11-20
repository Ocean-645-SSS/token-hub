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

    @Test void translatesImagesAndToolRoundTripWithDefaultLimit() {
        var request = new ChatCompletionRequest("claude", List.of(
                Map.of("role", "system", "content", "first"),
                Map.of("role", "system", "content", "second"),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AA==")))),
                Map.of("role", "assistant", "tool_calls", List.of(Map.of("type", "function", "id", "call1",
                        "function", Map.of("name", "lookup", "arguments", "{\"id\":1}")))),
                Map.of("role", "tool", "tool_call_id", "call1", "content", "found")),
                null, false, List.of(Map.of("type", "function", "function", Map.of("name", "lookup"))));
        var body = AnthropicProvider.toVendorBody(request);
        assertEquals("first\n\nsecond", body.get("system"));
        assertEquals(1024, body.get("max_tokens"));
        var messages = (List<?>) body.get("messages");
        assertEquals(Map.of("role", "user", "content", List.of(Map.of("type", "image", "source",
                Map.of("type", "base64", "media_type", "image/png", "data", "AA==")))), messages.get(0));
        assertEquals(Map.of("role", "assistant", "content", List.of(Map.of("type", "tool_use", "id", "call1",
                "name", "lookup", "input", Map.of("id", 1)))), messages.get(1));
        assertEquals(Map.of("role", "user", "content", List.of(Map.of("type", "tool_result",
                "tool_use_id", "call1", "content", "found"))), messages.get(2));
        assertEquals(List.of(Map.of("name", "lookup", "input_schema", Map.of("type", "object"))), body.get("tools"));

        var response = AnthropicProvider.fromVendorBody(Map.of("content", List.of(
                Map.of("type", "text", "text", "a"), Map.of("type", "text", "text", "b"),
                Map.of("type", "tool_use", "id", "call1", "name", "lookup", "input", Map.of("id", 1))),
                "stop_reason", "tool_use"));
        var choice = (Map<?, ?>) ((List<?>) response.body().get("choices")).get(0);
        var message = (Map<?, ?>) choice.get("message");
        assertEquals("ab", message.get("content"));
        assertEquals("tool_calls", choice.get("finish_reason"));
        assertEquals(List.of(Map.of("id", "call1", "type", "function",
                "function", Map.of("name", "lookup", "arguments", "{\"id\":1}"))), message.get("tool_calls"));
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
