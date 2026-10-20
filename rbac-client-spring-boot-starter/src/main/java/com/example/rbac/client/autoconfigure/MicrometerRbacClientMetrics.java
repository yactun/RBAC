package com.example.rbac.client.autoconfigure;

import com.example.rbac.client.RbacClientMetrics;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

/**
 * Micrometer-backed {@link RbacClientMetrics}. Used by the auto-configuration when
 * a {@code MeterRegistry} bean exists; host apps that wire the client manually can
 * construct it directly. Only loaded when Micrometer is present (it is an optional
 * starter dependency), so an L1-only host without Micrometer never touches it.
 *
 * <p>Meters:
 * <ul>
 *   <li>{@code rbac.client.cache} counter — tags {@code tier} (l1|l2), {@code result} (hit|miss)</li>
 *   <li>{@code rbac.client.decision} timer — tags {@code operation} (check|scope|ui|grants), {@code source} (l1|l2|pdp|error)</li>
 *   <li>{@code rbac.client.pdp.fetch} counter — tag {@code success} (true|false)</li>
 *   <li>{@code rbac.client.invalidation.events} counter</li>
 *   <li>{@code rbac.client.bus.up} gauge (1|0) + {@code rbac.client.bus.lost} counter (bus-outage alarm)</li>
 * </ul>
 */
public final class MicrometerRbacClientMetrics implements RbacClientMetrics {

    private final MeterRegistry registry;
    private volatile boolean busUp;

    public MicrometerRbacClientMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void cache(String tier, String result) {
        registry.counter("rbac.client.cache", "tier", tier, "result", result).increment();
    }

    @Override
    public void decision(String operation, String source, Duration took) {
        registry.timer("rbac.client.decision", "operation", operation, "source", source)
                .record(took);
    }

    @Override
    public void pdpFetch(boolean success) {
        registry.counter("rbac.client.pdp.fetch", "success", Boolean.toString(success)).increment();
    }

    @Override
    public void invalidationEvent() {
        registry.counter("rbac.client.invalidation.events").increment();
    }

    @Override
    public void busState(boolean active) {
        this.busUp = active;
        registry.gauge("rbac.client.bus.up", this, m -> m.busUp ? 1 : 0);
        if (!active) {
            registry.counter("rbac.client.bus.lost").increment();
        }
    }
}
