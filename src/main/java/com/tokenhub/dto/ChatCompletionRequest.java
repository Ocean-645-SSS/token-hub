package com.tokenhub.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
public record ChatCompletionRequest(@NotBlank String model, @NotEmpty List<Map<String, Object>> messages, @JsonProperty("max_tokens") Integer maxTokens, Boolean stream, List<Map<String, Object>> tools) {
    public ChatCompletionRequest(String model, List<Map<String, Object>> messages, Integer maxTokens, Boolean stream) {
        this(model, messages, maxTokens, stream, null);
    }
    public ChatCompletionRequest withModelAndMaxTokens(String upstreamModel, Integer limit) {
        return new ChatCompletionRequest(upstreamModel, messages, limit, stream, tools);
    }
}
