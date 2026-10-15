package com.example.rbac.service.cache;

import com.example.rbac.model.CacheKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for the global policy version.
 * Bumped (INCR) on every grant change; every cached value carries the version
 * it was written with, so a read can validate staleness cheaply (ADR D10).
 */
@Component
public class VersionService {

    private final StringRedisTemplate redis;

    public VersionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long current() {
        // Serializer-agnostic on purpose: this key is written by raw Redis (INCR), so
        // a template with a JSON value serializer hands back an Integer, not a String.
        Object v = redis.opsForValue().get(CacheKeys.POLICY_VERSION);
        return (v == null) ? 0L : Long.parseLong(v.toString().trim());
    }

    /** Bump and return the new version. */
    public long bump() {
        Long v = redis.opsForValue().increment(CacheKeys.POLICY_VERSION);
        return (v == null) ? 1L : v;
    }
}
