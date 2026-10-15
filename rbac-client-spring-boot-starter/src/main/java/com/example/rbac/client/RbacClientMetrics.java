package com.example.rbac.client;

import java.time.Duration;

/**
 * Metrics sink for the RBAC client (SLO evidence): cache hit-rate, decision
 * latency by serving source, PDP fetches, and received invalidation events.
 *
 * <p>Intentionally NOT a Micrometer type: the core client stays dependency-light
 * and must boot with <i>no</i> observability stack (L1-only hosts). The
 * auto-configuration provides a Micrometer-backed adapter when the host app has a
 * {@code MeterRegistry}; otherwise {@link #NONE} (no-op) is used. Host apps may
 * provide their own {@code RbacClientMetrics} bean to override.
 */
public interface RbacClientMetrics {

    /** No-op implementation — the default when the host has no Micrometer registry. */
    RbacClientMetrics NONE = new RbacClientMetrics() { };

    /**
     * Record a cache lookup outcome.
     * @param tier "l1" | "l2"
     * @param result "hit" | "miss"
     */
    default void cache(String tier, String result) { }

    /**
     * Record one decision (a {@code check}/{@code scope}/{@code ui}/{@code grants}
     * call) and its latency, including failed calls.
     * @param operation "check" | "scope" | "ui" | "grants"
     * @param source which tier served it: "l1" | "l2" | "pdp", or "error" if the call failed
     */
    default void decision(String operation, String source, Duration took) { }

    /**
     * Record a REST fetch of the grant set from the PDP (i.e. an L1/L2 miss).
     * @param success whether the fetch succeeded (false = fail-closed path taken)
     */
    default void pdpFetch(boolean success) { }

    /** Record an invalidation event received from the bus. */
    default void invalidationEvent() { }
}
