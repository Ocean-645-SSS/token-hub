package com.tokenhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class PublisherExecutorConfig {
    @Bean(name = "publisherExecutor")
    public ThreadPoolTaskExecutor publisherExecutor(MessagingProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.publisherCoreThreads());
        executor.setMaxPoolSize(properties.publisherMaxThreads());
        executor.setQueueCapacity(properties.publisherQueueCapacity());
        executor.setThreadNamePrefix("publisher-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.claimTimeoutSeconds());
        executor.initialize();
        return executor;
    }
}
