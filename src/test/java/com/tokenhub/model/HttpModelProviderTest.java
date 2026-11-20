package com.tokenhub.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tokenhub.dto.ChatCompletionRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HttpModelProviderTest {
    private final ChatCompletionRequest request =
            new ChatCompletionRequest("model", List.of(Map.of("role", "user", "content", "hi")), 10, false);

    @Test void readTimeoutAfterReceivingPostIsAnUnknownOutcome() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger received = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                received.incrementAndGet();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ModelProviderException error = assertThrows(ModelProviderException.class,
                    () -> provider(server, 1000).complete(request));
            assertEquals(1, received.get());
            assertTrue(error.outcomeUnknown());
            assertTrue(error.countsAgainstBreaker());
            assertFalse(error.retryable());
            assertFalse(error.allowsFailover());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test void distinguishesBadRequestThrottlingAndAmbiguousGatewayFailure() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger status = new AtomicInteger(400);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(status.get(), -1);
            }
        });
        server.start();
        try {
            var provider = provider(server, 2000);
            var rejected = assertThrows(ModelProviderException.class, () -> provider.complete(request));
            assertFalse(rejected.countsAgainstBreaker());
            assertFalse(rejected.allowsFailover());

            status.set(429);
            var throttled = assertThrows(ModelProviderException.class, () -> provider.complete(request));
            assertTrue(throttled.countsAgainstBreaker());
            assertTrue(throttled.allowsFailover());
            assertFalse(throttled.retryable());

            status.set(503);
            var unavailable = assertThrows(ModelProviderException.class, () -> provider.complete(request));
            assertTrue(unavailable.outcomeUnknown());
            assertFalse(unavailable.allowsFailover());
        } finally {
            server.stop(0);
        }
    }

    private ModelProvider provider(HttpServer server, int readTimeoutMs) {
        return new OpenAiCompatibleModelProvider("mock", "http://127.0.0.1:" + server.getAddress().getPort(),
                "", 2000, readTimeoutMs, new ObjectMapper());
    }
}
