package com.example.rbac.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REVIEW D2: the {@code @Async} audit executor must be BOUNDED — Boot's default
 * {@code applicationTaskExecutor} queues unboundedly, so a decision-path spike or a
 * slow/failed Postgres would grow memory without limit instead of applying the
 * backpressure ADR-001's risk table promises. The replacement is named
 * {@code applicationTaskExecutor} so plain {@code @Async} resolves to it, and it
 * rejects with {@link ThreadPoolExecutor.CallerRunsPolicy} (no dropped audit writes,
 * memory stays bounded, the caller pays the latency).
 */
class AuditAsyncConfigTest {

    @Test
    void auditExecutor_isBoundedWithCallerRunsBackpressure() {
        TaskExecutor executor = new AuditAsyncConfig().applicationTaskExecutor();

        assertThat(executor).as("the @Async executor is a pool (not the unbounded default)")
                .isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getQueueCapacity()).as("the audit queue is bounded").isEqualTo(1000);
        assertThat(pool.getThreadPoolExecutor().getRejectedExecutionHandler())
                .as("saturated queue = backpressure on the caller, never a dropped write")
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        assertThat(pool.getThreadNamePrefix()).isEqualTo("rbac-audit-");
        pool.shutdown();
    }

    @Test
    void callerRunsPolicy_runsTheOverflowOnTheCallingThread_andDropsNothing() {
        // Deterministic saturation check (no sleeps): a pool of 1 with a queue of 1.
        // The first task holds the worker (blocked on a latch), the second fills the
        // queue, and the last two are REJECTED to CallerRunsPolicy — which runs them
        // inline on the calling (test) thread. Counting the executing thread names
        // proves both the backpressure and that nothing is dropped.
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(1);
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Spring's shutdown() calls shutdownNow() (interrupts + DRAINS the queue) unless
        // this is true — with the default, the queued task below would be discarded and
        // the "no drop" assertion below could never hold.
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(10);
        pool.initialize();
        ThreadPoolExecutor underlying = pool.getThreadPoolExecutor();

        String main = Thread.currentThread().getName();
        java.util.concurrent.CountDownLatch holdWorker = new java.util.concurrent.CountDownLatch(1);
        java.util.List<String> ranOn = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AtomicInteger done = new AtomicInteger();
        Runnable task = () -> {
            boolean inline = Thread.currentThread().getName().equals(main);
            ranOn.add(Thread.currentThread().getName());
            if (!inline) {
                // Runs on a POOL thread: hold the worker busy until the test releases it,
                // so the pool stays saturated across the burst. Inline (main-thread)
                // tasks never block — that is exactly what we are measuring.
                try {
                    holdWorker.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            done.incrementAndGet();
        };

        pool.execute(task);   // 1: the worker (blocks on the latch)
        pool.execute(task);   // 2: the queue (now full)
        pool.execute(task);   // 3: REJECTED -> CallerRunsPolicy -> inline on the test thread
        pool.execute(task);   // 4: REJECTED -> CallerRunsPolicy -> inline on the test thread

        // The two inline runs already happened synchronously inside pool.execute(...).
        long inline = ranOn.stream().filter(main::equals).count();
        assertThat(inline).as("the overflow ran on the caller (backpressure), not silently dropped")
                .isEqualTo(2);

        holdWorker.countDown();
        pool.shutdown();
        try {
            assertThat(underlying.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertThat(done.get()).as("no task was dropped").isEqualTo(4);
    }
}
