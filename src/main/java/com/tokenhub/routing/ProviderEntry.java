package com.tokenhub.routing;

import com.tokenhub.model.ModelProvider;
import java.util.Map;

/**
 * One upstream provider candidate: its adapter, unit cost, circuit breaker and a
 * latency EMA. The unit cost is the platform's cost side (per token), used for
 * routing and cost analysis, not for user billing.
 */
public final class ProviderEntry {

    private final String code;
    private final ModelProvider provider;
    private final double unitCost;
    private final CircuitBreaker breaker;
    private final Map<String, String> models;
    private volatile double latencySeconds = 0.0d;

    public ProviderEntry(String code, ModelProvider provider, double unitCost, CircuitBreaker breaker) {
        this(code, provider, unitCost, breaker, Map.of());
    }

    public ProviderEntry(String code, ModelProvider provider, double unitCost,
                         CircuitBreaker breaker, Map<String, String> models) {
        this.models = Map.copyOf(models);
        this.code = code;
        this.provider = provider;
        this.unitCost = unitCost;
        this.breaker = breaker;
    }

    public boolean supports(String model) { return models.isEmpty() || models.containsKey(model); }
    public String upstreamModel(String model) { return models.getOrDefault(model, model); }

    public String code() { return code; }
    public ModelProvider provider() { return provider; }
    public double unitCost() { return unitCost; }
    public CircuitBreaker breaker() { return breaker; }
    public double latencySeconds() { return latencySeconds; }

    /** Exponential moving average over observed success latencies (alpha = 0.2). */
    public void recordLatencyMillis(long millis) {
        double seconds = millis / 1000.0d;
        double current = latencySeconds;
        latencySeconds = current == 0.0d ? seconds : current * 0.8d + seconds * 0.2d;
    }
}
