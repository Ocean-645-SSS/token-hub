package com.tokenhub.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.config.ModelProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRouterTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test void excludesOpenBreakerAndSortsByCost() {
        ProviderRegistry registry = registryWithCosts(0.0001, 0.0002, 0.0003);
        ProviderEntry cheapest = registry.entries().get(0);
        for (int i = 0; i < 3; i++) cheapest.breaker().recordFailure();

        List<ProviderEntry> order = new ProviderRouter(registry, new ModelProperties()).routeOrder();

        assertEquals(2, order.size());
        assertFalse(order.contains(cheapest));
        assertEquals("p1", order.get(0).code());
        assertEquals("p2", order.get(1).code());
    }

    @Test void lowerLatencyWinsWhenCostIsEqual() {
        ProviderRegistry registry = registryWithCosts(0.0001, 0.0001);
        ProviderEntry slow = registry.entries().get(0);
        slow.recordLatencyMillis(500); // 0.5s

        List<ProviderEntry> order = new ProviderRouter(registry, new ModelProperties()).routeOrder();

        assertEquals(2, order.size());
        assertEquals("p1", order.get(0).code());
        assertEquals("p0", order.get(1).code());
    }

    @Test void emptyProviderConfigFallsBackToSingleDefault() {
        ModelProperties props = new ModelProperties();
        props.setBaseUrl("http://127.0.0.1:18080");
        ProviderRegistry registry = new ProviderRegistry(props, json);

        assertEquals(1, registry.entries().size());
        assertEquals("default", registry.entries().get(0).code());
        assertSame(registry.entries().get(0).provider(), registry.defaultProvider());
    }

    @Test void cooldownRestoresOnlyCompatibleProviderForASingleProbe() {
        AtomicLong now = new AtomicLong();
        CircuitBreaker breaker = new CircuitBreaker(1, 10, now::get);
        ProviderEntry candidate = new ProviderEntry("mapped", null, 2, breaker, Map.of("public", "vendor"));
        ProviderEntry wrongModel = new ProviderEntry("other", null, 0, new CircuitBreaker(1, 10),
                Map.of("different", "different"));
        ProviderRegistry registry = mock(ProviderRegistry.class);
        when(registry.entries()).thenReturn(List.of(candidate, wrongModel));
        ProviderRouter router = new ProviderRouter(registry, new ModelProperties());

        var stale = breaker.acquire();
        breaker.acquire().complete(CircuitBreaker.Outcome.FAILURE);
        assertTrue(router.routeOrder("public").isEmpty());
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
        assertEquals(List.of(candidate), router.routeOrder("public"));
        assertEquals("vendor", candidate.upstreamModel("public"));
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        var probe = breaker.acquire();
        assertTrue(probe.probe());
        stale.complete(CircuitBreaker.Outcome.SUCCESS);
        assertNull(breaker.acquire());
        assertTrue(router.routeOrder("public").isEmpty());
        probe.complete(CircuitBreaker.Outcome.NEUTRAL);
        breaker.acquire().complete(CircuitBreaker.Outcome.SUCCESS);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    private ProviderRegistry registryWithCosts(double... costs) {
        ModelProperties props = new ModelProperties();
        List<ModelProperties.Provider> providers = new ArrayList<>();
        for (int i = 0; i < costs.length; i++) {
            ModelProperties.Provider p = new ModelProperties.Provider();
            p.setCode("p" + i);
            p.setBaseUrl("http://127.0.0.1:" + (18080 + i));
            p.setApiKey("mock-key");
            p.setUnitCost(costs[i]);
            providers.add(p);
        }
        props.setProviders(providers);
        return new ProviderRegistry(props, json);
    }
}
