package com.example.rbac.service.cache;

import com.example.rbac.model.CacheKeys;
import com.example.rbac.model.dto.GrantSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * L2 shared cache (Redis). The PDP is the ONLY writer of this cache (ADR D5);
 * clients read it directly via the starter. Keys are derived with the shared
 * {@link CacheKeys} scheme so both sides agree byte-for-byte.
 */
@Component
public class GrantCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public GrantCache(StringRedisTemplate redis, ObjectMapper json,
                      @org.springframework.beans.factory.annotation.Value("${rbac.cache.l2-ttl:10m}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    public GrantSet get(Set<String> profiles) {
        String raw = redis.opsForValue().get(CacheKeys.grantsKey(profiles));
        if (raw == null) {
            return null;
        }
        try {
            return json.readValue(raw, GrantSet.class);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt grant cache entry", e);
        }
    }

    public void put(Set<String> profiles, GrantSet grantSet) {
        try {
            redis.opsForValue().set(CacheKeys.grantsKey(profiles), json.writeValueAsString(grantSet), ttl);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write grant cache", e);
        }
    }

    public void evict(Set<String> profiles) {
        redis.delete(CacheKeys.grantsKey(profiles));
    }
}
