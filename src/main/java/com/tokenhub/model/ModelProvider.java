package com.tokenhub.model;
import com.tokenhub.dto.ChatCompletionRequest;
import java.util.Map;
public interface ModelProvider { ProviderResponse complete(ChatCompletionRequest request); record ProviderResponse(Map<String,Object> body, Long promptTokens, Long completionTokens, Long totalTokens) { } }
