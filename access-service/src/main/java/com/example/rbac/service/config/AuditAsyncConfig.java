package com.example.rbac.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * REVIEW D2: the {@code @Async} audit writes must run on a BOUNDED executor —
 * Boot's default {@code applicationTaskExecutor} has an unbounded queue
 * ({@code Integer.MAX_VALUE}), so a decision-path spike (or a slow/failed Postgres)
 * would grow memory without limit instead of applying the backpressure ADR-001's
 * risk table promises.
 *
 * <p>Named {@code applicationTaskExecutor} so it replaces Boot's auto-configured
 * default (its {@code @ConditionalOnMissingBean} backs off) and plain {@code @Async}
 * resolves to it. Bounded queue + {@link ThreadPoolExecutor.CallerRunsPolicy}:
 * when saturated, the CALLING thread runs the audit write inline — no task is
 * dropped (audit is mandatory), memory stays bounded, and the decision path pays
 * the latency (true backpressure).
 */
@Configuration
public class AuditAsyncConfig {

    /** Bounded audit-write executor (see class Javadoc for the backpressure policy). */
    @Bean
    public TaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("rbac-audit-");
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(1000);
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(5);
        ex.initialize();
        return ex;
    }
}
