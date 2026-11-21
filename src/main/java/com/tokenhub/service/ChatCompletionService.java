package com.tokenhub.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.common.BusinessException;
import com.tokenhub.common.ModelRequestStatus;
import com.tokenhub.common.ProviderCostStatus;
import com.tokenhub.config.BillingProperties;
import com.tokenhub.config.ModelProperties;
import com.tokenhub.dto.ChatCompletionRequest;
import com.tokenhub.entity.ApiKey;
import com.tokenhub.entity.ModelRequest;
import com.tokenhub.mapper.ModelRequestMapper;
import com.tokenhub.mapper.UserMapper;
import com.tokenhub.model.AnthropicProvider;
import com.tokenhub.model.ModelProvider;
import com.tokenhub.model.ModelProviderException;
import com.tokenhub.routing.CircuitBreaker;
import com.tokenhub.routing.ProviderEntry;
import com.tokenhub.routing.ProviderRouter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
@Service
public class ChatCompletionService {
    private static final Pattern TEXT_PARTS = Pattern.compile(
            "(?<word>[A-Za-z]+(?:['’][A-Za-z]+)*)|(?<han>\\p{IsHan})|[^\\p{IsWhite_Space}]");

    private final ApiKeyService keys;
    private final ApiKeyRateLimiter limiter;
    private final ModelRequestMapper requests;
    private final TokenReservationService reservations;
    private final UserMapper users;
    private final ProviderRouter router;
    private final BillingProperties billing;
    private final ModelProperties model;
    private final ObjectMapper json;

    public ChatCompletionService(ApiKeyService keys, ApiKeyRateLimiter limiter, ModelRequestMapper requests,
                                 TokenReservationService reservations, UserMapper users, ProviderRouter router,
                                 BillingProperties billing, ModelProperties model, ObjectMapper json) {
        this.keys = keys;
        this.limiter = limiter;
        this.requests = requests;
        this.reservations = reservations;
        this.users = users;
        this.router = router;
        this.billing = billing;
        this.model = model;
        this.json = json;
    }

    public Map<String, Object> complete(String authorization, String idempotencyKey, ChatCompletionRequest input) {
        ApiKey key = keys.authenticate(bearer(authorization));
        var user = users.findById(key.getUserId());
        if (user == null || user.getStatus() != 1) {
            throw new BusinessException("ACCOUNT_SUSPENDED", "account is suspended", HttpStatus.FORBIDDEN);
        }
        limiter.check(key.getId());
        validate(input, key, idempotencyKey);

        long reserved = estimate(input);
        ModelRequest prior = requests.findByRequestId(idempotencyKey);
        if (prior != null) return prior(prior, key);

        ModelRequest request = new ModelRequest();
        request.setRequestId(idempotencyKey);
        request.setUserId(key.getUserId());
        request.setApiKeyId(key.getId());
        request.setModelCode(input.model());
        request.setReservedTokens(reserved);
        request.setStatus(ModelRequestStatus.PROCESSING);
        request.setRetryCount(0);
        try {
            reservations.freeze(request);
        } catch (DuplicateKeyException e) {
            ModelRequest existing = requests.findByRequestId(idempotencyKey);
            if (existing == null) throw e;
            return prior(existing, key);
        }
        ModelProvider.ProviderResponse response;
        try {
            response = callWithFailover(input, request);
        } catch (ModelProviderException e) {
            if (e.outcomeUnknown()) {
                finish(request, ModelRequestStatus.BILLING_UNKNOWN, null, null);
                throw billingUnknown();
            }
            finish(request, ModelRequestStatus.FAILED, null, null);
            if (e.clientInputInvalid()) {
                throw new BusinessException("INVALID_REQUEST", e.getMessage(), HttpStatus.BAD_REQUEST);
            }
            throw new BusinessException("MODEL_TEMPORARILY_UNAVAILABLE", "model provider unavailable",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        if (response.totalTokens() == null || response.totalTokens() < 0) {
            finish(request, ModelRequestStatus.BILLING_UNKNOWN, response, null);
            throw billingUnknown();
        }
        String result;
        try {
            result = json.writeValueAsString(response.body());
        } catch (JsonProcessingException e) {
            finish(request, ModelRequestStatus.BILLING_UNKNOWN, response, null);
            throw billingUnknown();
        }
        if (!finish(request, ModelRequestStatus.SUCCESS, response, result)) throw billingUnknown();
        return response.body();
    }

    private ModelProvider.ProviderResponse callWithFailover(ChatCompletionRequest input, ModelRequest request) {
        ModelProviderException last = null;
        for (ProviderEntry entry : router.routeOrder(input.model())) {
            CircuitBreaker.Permit permit = entry.breaker().acquire();
            if (permit == null) continue;
            var outcome = CircuitBreaker.Outcome.NEUTRAL;
            try {
                ChatCompletionRequest upstream = input.withModelAndMaxTokens(entry.upstreamModel(input.model()),
                        input.maxTokens() == null && entry.provider() instanceof AnthropicProvider
                                ? Math.min(1024, model.getMaxOutputTokens()) : input.maxTokens());
                long start = System.nanoTime();
                var response = callProviderWithRetry(entry, upstream, request, permit.probe());
                entry.recordLatencyMillis((System.nanoTime() - start) / 1_000_000L);
                outcome = CircuitBreaker.Outcome.SUCCESS;
                return response;
            } catch (ModelProviderException e) {
                last = e;
                if (e.countsAgainstBreaker()) outcome = CircuitBreaker.Outcome.FAILURE;
                if (!e.allowsFailover() || Thread.currentThread().isInterrupted()) throw e;
            } finally {
                permit.complete(outcome);
            }
        }
        if (last != null) throw last;
        throw new ModelProviderException("all providers unavailable", false, null);
    }

    private ModelProvider.ProviderResponse callProviderWithRetry(ProviderEntry entry, ChatCompletionRequest input,
                                                                 ModelRequest request, boolean probe) {
        int maxAttempts = probe ? 1 : Math.max(1, model.getMaxAttempts());
        for (int attempt = 1; ; attempt++) {
            if (request.getProviderCode() != null) request.setRetryCount(request.getRetryCount() + 1);
            request.setProviderCode(entry.code());
            if (requests.recordAttempt(request) == 0) throw billingUnknown();
            try {
                return entry.provider().complete(input);
            } catch (ModelProviderException e) {
                if (!e.retryable() || attempt >= maxAttempts) throw e;
                try {
                    Thread.sleep(attempt == 1 ? 200 : 500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private boolean finish(ModelRequest request, ModelRequestStatus status,
                           ModelProvider.ProviderResponse response, String result) {
        request.setStatus(status);
        request.setInputTokens(response == null ? null : response.promptTokens());
        request.setOutputTokens(response == null ? null : response.completionTokens());
        request.setTotalTokens(response == null || response.totalTokens() == null || response.totalTokens() < 0
                ? null : response.totalTokens());
        request.setProviderCostStatus(status == ModelRequestStatus.SUCCESS
                ? ProviderCostStatus.KNOWN : ProviderCostStatus.UNKNOWN);
        request.setResultReference(result);
        return reservations.finish(request);
    }

    private static BusinessException billingUnknown() {
        return new BusinessException("BILLING_UNKNOWN", "request has unknown billing", HttpStatus.BAD_GATEWAY);
    }
    private Map<String, Object> prior(ModelRequest request, ApiKey key) {
        if (!Objects.equals(request.getApiKeyId(), key.getId())) {
            throw new BusinessException("IDEMPOTENCY_KEY_REUSE", "Idempotency-Key is already in use", HttpStatus.CONFLICT);
        }
        return switch (request.getStatus()) {
            case SUCCESS -> readStoredResult(request);
            case PROCESSING -> throw new BusinessException("REQUEST_PROCESSING", "request is processing", HttpStatus.CONFLICT);
            case BILLING_UNKNOWN -> throw billingUnknown();
            case FAILED -> throw new BusinessException("IDEMPOTENCY_KEY_REUSE",
                    "failed request requires a new Idempotency-Key", HttpStatus.CONFLICT);
        };
    }

    private Map<String, Object> readStoredResult(ModelRequest request) {
        try {
            return json.readValue(request.getResultReference(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored completion unavailable", e);
        }
    }

    private void validate(ChatCompletionRequest request, ApiKey key, String id) {
        if (id == null || id.isBlank() || id.length() > 64) {
            throw new BusinessException("INVALID_REQUEST", "Idempotency-Key is required", HttpStatus.BAD_REQUEST);
        }
        if (Boolean.TRUE.equals(request.stream())) {
            throw new BusinessException("INVALID_REQUEST", "streaming is not supported", HttpStatus.BAD_REQUEST);
        }
        Integer maxTokens = request.maxTokens();
        if (maxTokens != null && (maxTokens < 1 || maxTokens > model.getMaxOutputTokens())) {
            throw new BusinessException("INVALID_REQUEST", "invalid max_tokens", HttpStatus.BAD_REQUEST);
        }
        if (!Arrays.asList(key.getAllowedModels().split(",")).contains(request.model())) {
            throw new BusinessException("MODEL_NOT_ALLOWED", "model is not allowed for this api key", HttpStatus.FORBIDDEN);
        }
        if (request.messages().stream().anyMatch(Objects::isNull)
                || request.tools() != null && request.tools().stream().anyMatch(Objects::isNull)) {
            throw new BusinessException("INVALID_REQUEST", "messages and tools must contain objects", HttpStatus.BAD_REQUEST);
        }
    }

    private long estimate(ChatCompletionRequest request) {
        long chars = request.messages().stream().mapToLong(message ->
                String.valueOf(message.getOrDefault("content", "")).length()
                        + (long) Objects.toString(message.get("tool_calls"), "").length()).sum();
        if (request.tools() != null) chars += request.tools().toString().length();
        if (chars > model.getMaxInputCharacters()) {
            throw new BusinessException("INVALID_REQUEST", "input is too large", HttpStatus.BAD_REQUEST);
        }
        int output = request.maxTokens() == null ? model.getMaxOutputTokens() : request.maxTokens();
        double input = 0.0;
        for (Map<String, Object> message : request.messages()) {
            input += estimateContent(message.get("content"));
            input += Objects.toString(message.get("tool_calls"), "").length() / 4.0;
        }
        if (request.tools() != null) input += request.tools().toString().length() / 4.0;
        return Math.max(1, (long) Math.ceil(input)) + output + billing.getSafetyMargin();
    }

    private static double estimateContent(Object content) {
        if (content instanceof String text) return estimateText(text);
        if (content instanceof List<?> blocks) {
            double tokens = 0.0;
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> part && "text".equals(part.get("type"))
                        && part.get("text") instanceof String text) {
                    tokens += estimateText(text);
                } else {
                    tokens += String.valueOf(block).length() / 4.0;
                }
            }
            return tokens;
        }
        return Objects.toString(content, "").length() / 4.0;
    }

    private static double estimateText(String text) {
        var parts = TEXT_PARTS.matcher(text);
        int han = 0;
        int words = 0;
        int other = 0;
        while (parts.find()) {
            if (parts.group("han") != null) han++;
            else if (parts.group("word") != null) words++;
            else other++;
        }
        return han / 1.8 + words / 0.75 + other;
    }

    private static String bearer(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw new BusinessException("INVALID_API_KEY", "invalid api key", HttpStatus.UNAUTHORIZED);
        }
        return header.substring(7).trim();
    }
}
