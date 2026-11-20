package com.tokenhub.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.dto.ChatCompletionRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Map;

import static com.tokenhub.model.ModelProviderException.Kind.*;

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
        Map<String, Object> payload = buildBody(request);
        try {
            String raw = client.post().uri(path())
                    .headers(this::applyHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve()
                    .onStatus(status -> status.is3xxRedirection(), (sent, received) -> {
                        throw new ModelProviderException("unexpected provider redirect", REQUEST_REJECTED, null);
                    }).body(String.class);
            return parse(raw);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            var kind = status == 429 ? THROTTLED
                    : isExplicitOverload(status) ? OVERLOADED
                    : status == 408 || status >= 500 ? OUTCOME_UNKNOWN : REQUEST_REJECTED;
            throw new ModelProviderException("provider http failure", kind, e);
        } catch (ResourceAccessException e) {
            Throwable cause = e.getMostSpecificCause();
            var kind = cause instanceof ConnectException || cause instanceof UnknownHostException
                    ? CONNECT_FAILED : OUTCOME_UNKNOWN;
            throw new ModelProviderException("provider network failure", kind, e);
        } catch (ModelProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelProviderException("provider response failure", OUTCOME_UNKNOWN, e);
        }
    }

    protected abstract String path();
    protected abstract void applyHeaders(HttpHeaders headers);
    protected abstract Map<String, Object> buildBody(ChatCompletionRequest request);
    protected abstract ProviderResponse parse(String raw) throws Exception;
    protected boolean isExplicitOverload(int status) { return false; }

    protected ObjectMapper json() { return json; }
    protected String apiKey() { return apiKey; }
    protected static Long number(Object v) { return v instanceof Number n ? n.longValue() : null; }
}
