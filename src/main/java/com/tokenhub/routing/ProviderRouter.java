package com.tokenhub.routing;

import com.tokenhub.config.ModelProperties;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Orders upstream provider candidates by a cost + latency score (lower is better)
 * and skips providers temporarily blocked by their circuit breaker. Weights are configuration
 * driven; equal weights are the demo default and would be calibrated in production.
 */
@Component
public class ProviderRouter {

    private final ProviderRegistry registry;
    private final double costWeight;
    private final double latencyWeight;

    public ProviderRouter(ProviderRegistry registry, ModelProperties props) {
        this.registry = registry;
        this.costWeight = props.getRouting().getCostWeight();
        this.latencyWeight = props.getRouting().getLatencyWeight();
    }

    /** Candidates that may serve a request right now, best first. */
    public List<ProviderEntry> routeOrder() {
        return routeOrder(null);
    }

    public List<ProviderEntry> routeOrder(String model) {
        return registry.entries().stream()
                .filter(e -> model == null || e.supports(model))
                .filter(e -> e.breaker().eligible())
                .sorted(Comparator.comparingDouble(this::score))
                .toList();
    }

    private double score(ProviderEntry e) {
        return costWeight * e.unitCost() + latencyWeight * e.latencySeconds();
    }
}
