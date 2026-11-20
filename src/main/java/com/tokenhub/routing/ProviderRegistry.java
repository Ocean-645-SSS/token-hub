package com.tokenhub.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.config.ModelProperties;
import com.tokenhub.model.AnthropicProvider;
import com.tokenhub.model.ModelProvider;
import com.tokenhub.model.OpenAiCompatibleModelProvider;
import com.tokenhub.model.ProviderAdapterFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the upstream provider candidates from configuration. When no providers
 * are configured, falls back to a single "default" provider (backward compatible
 * with the previous single-endpoint behavior).
 */
@Component
public class ProviderRegistry {

    private final List<ProviderEntry> entries = new ArrayList<>();
    private final ModelProvider defaultProvider;

    public ProviderRegistry(ModelProperties props, ObjectMapper json) {
        ModelProperties.CircuitBreaker cb = props.getCircuitBreaker();
        if (props.getProviders() != null) {
            for (ModelProperties.Provider p : props.getProviders()) {
                ModelProvider provider = ProviderAdapterFactory.create(
                        p, props.getConnectTimeoutMs(), props.getReadTimeoutMs(), json);
                if (p.getModels().isEmpty() && provider instanceof AnthropicProvider) {
                    throw new IllegalArgumentException("Anthropic provider requires model mappings: " + p.getCode());
                }
                if (p.getModels().entrySet().stream().anyMatch(e -> e.getKey().isBlank()
                        || e.getValue() == null || e.getValue().isBlank())) {
                    throw new IllegalArgumentException("Invalid provider model mapping: " + p.getCode());
                }
                entries.add(new ProviderEntry(p.getCode(), provider, p.getUnitCost(),
                        new CircuitBreaker(cb.getFailureThreshold(), cb.getCooldownMs()), p.getModels()));
            }
        }
        if (entries.isEmpty()) {
            ModelProvider provider = new OpenAiCompatibleModelProvider(
                    "default", props.getBaseUrl(), props.getApiKey(),
                    props.getConnectTimeoutMs(), props.getReadTimeoutMs(), json);
            entries.add(new ProviderEntry("default", provider, 0.0d,
                    new CircuitBreaker(cb.getFailureThreshold(), cb.getCooldownMs())));
        }
        this.defaultProvider = entries.get(0).provider();
    }

    public List<ProviderEntry> entries() { return List.copyOf(entries); }

    /** Provider used for non-routed model calls (e.g. the AI guide's LLM). */
    public ModelProvider defaultProvider() { return defaultProvider; }
}
