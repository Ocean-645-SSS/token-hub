package com.tokenhub.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.dto.ChatCompletionRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;

import java.util.Map;

/**
 * Shared HTTP plumbing for vendor adapters: timeouts, error mapping, and the
 * unified complete() contract. Each vendor adapter only implements request/response
 * translation, so the router, circuit breaker and billing never see the vendor's
 * native protocol.
 */
public abstract class HttpModelProvider implements ModelProvider {

    private final String code;
    private final String apiKey;
    private final RestClient client;
    private final ObjectMapper json;

    protected HttpModelProvider(String code, String baseUrl, String apiKey,
                                int connectTimeoutMs, int readTimeoutMs, ObjectMapper json) {
        this.code = code;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.json = json;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectTimeoutMs);
        f.setReadTimeout(readTimeoutMs);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override public final String code() { return code; }

    @Override public final ProviderResponse complete(ChatCompletionRequest request) {
        try {
            String raw = client.post().uri(path())
                    .headers(this::applyHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildBody(request)).retrieve().body(String.class);
            return parse(raw);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            throw new ModelProviderException("provider http failure", isRetryable(status), e);
        } catch (ResourceAccessException e) {
            throw new ModelProviderException("provider network failure", true, e);
        } catch (ModelProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelProviderException("provider response failure", false, e);
        }
    }

    protected abstract String path();
    protected abstract void applyHeaders(HttpHeaders headers);
    protected abstract Map<String, Object> buildBody(ChatCompletionRequest request);
    protected abstract ProviderResponse parse(String raw) throws Exception;
    protected abstract boolean isRetryable(int status);

    protected ObjectMapper json() { return json; }
    protected String apiKey() { return apiKey; }
    protected static Long number(Object v) { return v instanceof Number n ? n.longValue() : null; }
}
