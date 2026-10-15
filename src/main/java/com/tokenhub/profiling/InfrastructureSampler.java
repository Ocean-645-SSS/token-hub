package com.tokenhub.profiling;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Dedicated, opt-in infrastructure sampler; it must not share Spring's scheduler. */
@Component
public final class InfrastructureSampler {
    private static final Logger log = LoggerFactory.getLogger(InfrastructureSampler.class);
    private final DataSource dataSource;
    private ScheduledExecutorService executor;

    public InfrastructureSampler(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void start() {
        if (!SeckillTrace.enabled()) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "tokenhub-profiling-sampler");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::sampleHikari, 0, 100, TimeUnit.MILLISECONDS);
    }

    private void sampleHikari() {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            log.info("hikari-profile datasourceType={}", dataSource.getClass().getName());
            return;
        }
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        if (pool == null) return;
        log.info("hikari-profile active={} idle={} total={} waiting={} max={} min={}",
                pool.getActiveConnections(), pool.getIdleConnections(), pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection(), hikari.getMaximumPoolSize(), hikari.getMinimumIdle());
    }

    @PreDestroy
    void stop() {
        if (executor != null) executor.shutdownNow();
    }
}
