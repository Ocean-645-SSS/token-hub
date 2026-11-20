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
        return toVendorBody(request, json());
    }

    @Override protected ProviderResponse parse(String raw) throws Exception {
        Map<String, Object> vendor = json().readValue(raw, new TypeReference<>() { });
        return fromVendorBody(vendor, json());
    }

    @Override protected boolean isExplicitOverload(int status) {
        return status == 529;
    }

    static Map<String, Object> toVendorBody(ChatCompletionRequest request) {
        return toVendorBody(request, new ObjectMapper());
    }

    private static Map<String, Object> toVendorBody(ChatCompletionRequest request, ObjectMapper json) {
        List<Map<String, Object>> messages = new ArrayList<>();
        List<String> system = new ArrayList<>();
        for (Map<String, Object> message : request.messages()) {
            Object role = message.get("role");
            Object content = message.get("content");
            if ("system".equals(role) || "developer".equals(role)) {
                for (Map<String, Object> block : blocks(content)) {
                    if (!"text".equals(block.get("type"))) throw invalidInput("system messages must contain text");
                    system.add((String) block.get("text"));
                }
            } else if ("tool".equals(role)) {
                messages.add(Map.of("role", "user", "content", List.of(Map.of(
                        "type", "tool_result", "tool_use_id", requiredString(message, "tool_call_id"),
                        "content", content instanceof String text ? text : blocks(content)))));
            } else if ("user".equals(role) || "assistant".equals(role)) {
                messages.add(Map.of("role", role, "content", messageContent(message, json)));
            } else {
                throw invalidInput("unsupported message role");
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        Integer maxTokens = request.maxTokens();
        body.put("max_tokens", maxTokens == null ? 1024 : maxTokens);
        body.put("messages", messages);
        if (!system.isEmpty()) body.put("system", String.join("\n\n", system));
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(AnthropicProvider::toolDefinition).toList());
        }
        return body;
    }

    private static Object messageContent(Map<String, Object> message, ObjectMapper json) {
        Object content = message.get("content");
        Object calls = message.get("tool_calls");
        if (content instanceof String text && calls == null) return text;
        List<Map<String, Object>> result = content == null ? new ArrayList<>() : blocks(content);
        if (calls != null) {
            if (!"assistant".equals(message.get("role")) || !(calls instanceof List<?> list)) {
                throw invalidInput("tool_calls must be a list on assistant messages");
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> call) || !"function".equals(call.get("type"))
                        || !(call.get("function") instanceof Map<?, ?> function)) {
                    throw invalidInput("invalid function tool call");
                }
                result.add(Map.of("type", "tool_use", "id", requiredString(call, "id"),
                        "name", requiredString(function, "name"), "input", toolInput(function, json)));
            }
        }
        if (result.isEmpty()) throw invalidInput("message content is required");
        return result;
    }

    private static List<Map<String, Object>> blocks(Object content) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (content instanceof String text) {
            if (!text.isEmpty()) result.add(Map.of("type", "text", "text", text));
            return result;
        }
        if (!(content instanceof List<?> items)) throw invalidInput("invalid message content");
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> block)) throw invalidInput("invalid content block");
            if ("text".equals(block.get("type")) && block.get("text") instanceof String text) {
                if (!text.isEmpty()) result.add(Map.of("type", "text", "text", text));
            } else if ("image_url".equals(block.get("type")) && block.get("image_url") instanceof Map<?, ?> image) {
                result.add(Map.of("type", "image", "source", imageSource(requiredString(image, "url"))));
            } else {
                throw invalidInput("unsupported content block");
            }
        }
        return result;
    }

    private static Map<String, Object> imageSource(String url) {
        if (url.startsWith("https://") || url.startsWith("http://")) {
            return Map.of("type", "url", "url", url);
        }
        int separator = url.indexOf(";base64,");
        if (!url.startsWith("data:image/") || separator < 0 || separator + 8 == url.length()) {
            throw invalidInput("image_url requires an HTTP URL or a base64 image data URL");
        }
        return Map.of("type", "base64", "media_type", url.substring(5, separator),
                "data", url.substring(separator + 8));
    }

    private static Map<String, Object> toolDefinition(Map<String, Object> tool) {
        if (!"function".equals(tool.get("type")) || !(tool.get("function") instanceof Map<?, ?> function)) {
            throw invalidInput("unsupported tool definition");
        }
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", requiredString(function, "name"));
        if (function.get("description") instanceof String description) definition.put("description", description);
        definition.put("input_schema", function.get("parameters") instanceof Map<?, ?> schema
                ? schema : Map.of("type", "object"));
        return definition;
    }

    private static Map<String, Object> toolInput(Map<?, ?> function, ObjectMapper json) {
        try {
            Map<String, Object> input = json.readValue(requiredString(function, "arguments"), new TypeReference<>() {});
            if (input != null) return input;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalidInput("function arguments must be a JSON object");
        }
        throw invalidInput("function arguments must be a JSON object");
    }

    private static String requiredString(Map<?, ?> object, String field) {
        if (object.get(field) instanceof String text && !text.isBlank()) return text;
        throw invalidInput("missing " + field);
    }

    private static ModelProviderException invalidInput(String message) {
        return new ModelProviderException(message, ModelProviderException.Kind.INVALID_INPUT, null);
    }

    static ProviderResponse fromVendorBody(Map<String, Object> vendor) {
        try {
            return fromVendorBody(vendor, new ObjectMapper());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("invalid Anthropic tool response", e);
        }
    }

    private static ProviderResponse fromVendorBody(Map<String, Object> vendor, ObjectMapper json)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> calls = new ArrayList<>();
        if (!(vendor.get("content") instanceof List<?> content)) {
            throw new IllegalArgumentException("missing Anthropic response content");
        }
        for (Object item : content) {
            if (!(item instanceof Map<?, ?> block)) continue;
            if ("text".equals(block.get("type")) && block.get("text") instanceof String value) {
                text.append(value);
            } else if ("tool_use".equals(block.get("type"))) {
                calls.add(Map.of("id", block.get("id"), "type", "function", "function", Map.of(
                        "name", block.get("name"), "arguments", json.writeValueAsString(block.get("input")))));
            }
        }
        Map<?, ?> usage = vendor.get("usage") instanceof Map<?, ?> map ? map : Map.of();
        Long input = number(usage.get("input_tokens"));
        Long output = number(usage.get("output_tokens"));
        Long total = input != null && output != null ? input + output : null;
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", calls.isEmpty() || !text.isEmpty() ? text.toString() : null);
        if (!calls.isEmpty()) message.put("tool_calls", calls);
        String reason = switch (Objects.toString(vendor.get("stop_reason"), "")) {
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            default -> "stop";
        };
        Map<String, Object> body = Map.of("choices", List.of(Map.of(
                "index", 0, "message", message, "finish_reason", reason)));
        return new ProviderResponse(body, input, output, total);
    }
}
