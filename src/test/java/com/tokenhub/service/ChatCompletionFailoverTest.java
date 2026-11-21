package com.tokenhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.common.BusinessException;
import com.tokenhub.common.ModelRequestStatus;
import com.tokenhub.config.BillingProperties;
import com.tokenhub.config.ModelProperties;
import com.tokenhub.dto.ChatCompletionRequest;
import com.tokenhub.entity.ApiKey;
import com.tokenhub.entity.ModelRequest;
import com.tokenhub.entity.User;
import com.tokenhub.mapper.ModelRequestMapper;
import com.tokenhub.mapper.UserMapper;
import com.tokenhub.model.ModelProvider;
import com.tokenhub.model.ModelProviderException;
import com.tokenhub.routing.CircuitBreaker;
import com.tokenhub.routing.ProviderEntry;
import com.tokenhub.routing.ProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static com.tokenhub.model.ModelProviderException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatCompletionFailoverTest {
    private final ModelProvider first = mock(ModelProvider.class);
    private final ModelProvider second = mock(ModelProvider.class);
    private final TokenReservationService reservations = mock(TokenReservationService.class);
    private final ModelRequestMapper requests = mock(ModelRequestMapper.class);
    private final CircuitBreaker firstBreaker = new CircuitBreaker(1, 10_000);
    private final ChatCompletionRequest input =
            new ChatCompletionRequest("public", List.of(Map.of("role", "user", "content", "hi")), 10, false);
    private ChatCompletionService service;

    @BeforeEach void prepare() {
        var keys = mock(ApiKeyService.class);
        var key = new ApiKey();
        key.setId(11L);
        key.setUserId(7L);
        key.setAllowedModels("public");
        when(keys.authenticate("key")).thenReturn(key);

        var users = mock(UserMapper.class);
        var user = new User();
        user.setStatus(1);
        when(users.findById(7L)).thenReturn(user);
        var router = mock(ProviderRouter.class);
        when(router.routeOrder("public")).thenReturn(List.of(
                new ProviderEntry("first", first, 0, firstBreaker, Map.of("public", "vendor-a")),
                new ProviderEntry("second", second, 1, new CircuitBreaker(1, 10000), Map.of("public", "vendor-b"))));
        when(requests.recordAttempt(any())).thenReturn(1);
        when(reservations.finish(any())).thenReturn(true);
        service = new ChatCompletionService(keys, mock(ApiKeyRateLimiter.class), requests, reservations,
                users, router, new BillingProperties(), new ModelProperties(), new ObjectMapper());
    }

    @Test void unknownResponseStopsRetriesAndFailoverAndPersistsReconciliationData() {
        when(first.complete(any())).thenThrow(new ModelProviderException("timeout", OUTCOME_UNKNOWN, null));

        assertEquals("BILLING_UNKNOWN", assertThrows(BusinessException.class,
                () -> service.complete("Bearer key", "request", input)).code());

        verify(first).complete(input.withModelAndMaxTokens("vendor-a", 10));
        verifyNoInteractions(second);
        var finished = finishedRequest();
        assertEquals(ModelRequestStatus.BILLING_UNKNOWN, finished.getStatus());
        assertEquals("first", finished.getProviderCode());
        assertEquals(0, finished.getRetryCount());
        assertEquals(CircuitBreaker.State.OPEN, firstBreaker.state());
    }

    @Test void throttlingSwitchesOnceAndSettlesTheSuccessfulVendor() {
        when(first.complete(any())).thenThrow(new ModelProviderException("throttled", THROTTLED, null));
        var body = Map.<String, Object>of("answer", "ok");
        when(second.complete(any())).thenReturn(new ModelProvider.ProviderResponse(body, 2L, 1L, 3L));

        assertEquals(body, service.complete("Bearer key", "request", input));

        verify(first).complete(input.withModelAndMaxTokens("vendor-a", 10));
        verify(second).complete(input.withModelAndMaxTokens("vendor-b", 10));
        var finished = finishedRequest();
        assertEquals(ModelRequestStatus.SUCCESS, finished.getStatus());
        assertEquals("second", finished.getProviderCode());
        assertEquals(1, finished.getRetryCount());
        assertEquals(3L, finished.getTotalTokens());
    }

    @Test void requestRejectionLeavesCircuitClosed() {
        when(first.complete(any())).thenThrow(new ModelProviderException("bad request", REQUEST_REJECTED, null));

        assertThrows(BusinessException.class, () -> service.complete("Bearer key", "request", input));

        verifyNoInteractions(second);
        assertEquals(ModelRequestStatus.FAILED, finishedRequest().getStatus());
        assertEquals(CircuitBreaker.State.CLOSED, firstBreaker.state());
    }

    @Test void freezesEstimatedChineseAndEnglishTextButSettlesActualUsage() {
        var mixed = new ChatCompletionRequest("public", List.of(
                Map.of("role", "system", "content", "中".repeat(1800)),
                Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", "one two three!")))),
                10, false);
        when(first.complete(any())).thenReturn(new ModelProvider.ProviderResponse(Map.of("answer", "ok"), 9L, 2L, 11L));

        service.complete("Bearer key", "mixed", mixed);

        // 1000 Chinese + 4 English + 1 punctuation + 10 output + 512 safety margin.
        verify(reservations).freeze(argThat(request -> request.getReservedTokens() == 1527L));
        assertEquals(11L, finishedRequest().getTotalTokens());
    }

    private ModelRequest finishedRequest() {
        var capture = ArgumentCaptor.forClass(ModelRequest.class);
        verify(reservations).finish(capture.capture());
        return capture.getValue();
    }
}
