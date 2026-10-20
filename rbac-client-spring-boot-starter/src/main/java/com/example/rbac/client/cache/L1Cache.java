package com.example.rbac.client.cache;

import com.example.rbac.model.CacheKeys;
import com.example.rbac.model.dto.GrantSet;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * L1 in-process cache (Caffeine). Per-replica, bounded by TTL (ADR D5, D10).
 * Invalidation (RabbitMQ) or a stale version clears it; TTL is the safety net.
 */
public class L1Cache {

    private final Cache<String, GrantSet> cache;

    public L1Cache(Duration ttl, long maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    public GrantSet get(Set<String> profiles) {
        return cache.getIfPresent(CacheKeys.grantsKey(profiles));
    }

    public void put(Set<String> profiles, GrantSet grantSet) {
        cache.put(CacheKeys.grantsKey(profiles), grantSet);
    }

    /**
     * Load-on-miss with Caffeine's per-key locking (C5b, stampede protection):
     * concurrent misses for the same key coalesce into a single {@code loader}
     * execution — the first caller runs it, the others block and receive its
     * result. A loader failure stores nothing and propagates to every waiter
     * (fail-closed is preserved).
     */
    public GrantSet getOrLoad(Set<String> profiles, Supplier<GrantSet> loader) {
        return cache.get(CacheKeys.grantsKey(profiles), key -> loader.get());
    }

    /** Drop one profile-set's entry (scoped invalidation). */
    public void evict(Set<String> profiles) {
        cache.invalidate(CacheKeys.grantsKey(profiles));
    }

    /** Drop everything (global invalidation). */
    public void clear() {
        cache.invalidateAll();
    }
}
