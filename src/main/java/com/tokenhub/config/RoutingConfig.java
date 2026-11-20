package com.tokenhub.config;

import com.tokenhub.model.ModelProvider;
import com.tokenhub.routing.ProviderRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes a default ModelProvider bean for non-routed callers (the AI guide's LLM). */
@Configuration
public class RoutingConfig {

    @Bean
    public ModelProvider defaultModelProvider(ProviderRegistry registry) {
        return registry.defaultProvider();
    }
}
