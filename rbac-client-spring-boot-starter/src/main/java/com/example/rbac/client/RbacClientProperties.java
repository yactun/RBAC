package com.example.rbac.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Client-side knobs (prefix {@code rbac.client}). */
@ConfigurationProperties(prefix = "rbac.client")
public class RbacClientProperties {

    /** Base URL of the PDP, e.g. http://access-service. */
    private String baseUrl = "http://localhost:8080";

    /** L1 (Caffeine) TTL. Bounded staleness fallback when the bus is down. */
    private java.time.Duration l1Ttl = java.time.Duration.ofSeconds(60);

    /** Max entries in the L1 cache. */
    private long l1MaxSize = 20_000;

    /** Enable the RabbitMQ invalidation subscriber (near-instant revocation). */
    private boolean invalidationEnabled = true;

    /** RabbitMQ queue for the invalidation subscriber. Leave {@code null} (default)
     *  to get a unique per-instance auto-delete queue — the correct broadcast pattern
     *  so EVERY replica receives each invalidation. Set it only if you intentionally
     *  want a shared (competitive-consumption) queue across instances. */
    private String invalidationQueue = null;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public java.time.Duration getL1Ttl() {
        return l1Ttl;
    }

    public void setL1Ttl(java.time.Duration l1Ttl) {
        this.l1Ttl = l1Ttl;
    }

    public long getL1MaxSize() {
        return l1MaxSize;
    }

    public void setL1MaxSize(long l1MaxSize) {
        this.l1MaxSize = l1MaxSize;
    }

    public boolean isInvalidationEnabled() {
        return invalidationEnabled;
    }

    public void setInvalidationEnabled(boolean invalidationEnabled) {
        this.invalidationEnabled = invalidationEnabled;
    }

    public String getInvalidationQueue() {
        return invalidationQueue;
    }

    public void setInvalidationQueue(String invalidationQueue) {
        this.invalidationQueue = invalidationQueue;
    }
}
