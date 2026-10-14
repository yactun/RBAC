package com.example.rbac.client.cache;

import com.example.rbac.model.CacheKeys;
import com.example.rbac.model.dto.GrantSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

/**
 * L2 shared cache (Redis). The PDP is the only writer; the client READS it
 * directly — no REST hop on a hit (ADR D5). Uses the shared {@link CacheKeys}
 * scheme so the key matches byte-for-byte.
 */
public class L2Cache {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public L2Cache(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public GrantSet get(String userId, Set<String> profiles) {
        String raw = redis.opsForValue().get(CacheKeys.grantsKey(userId, profiles));
        if (raw == null) {
            return null;
        }
        try {
            return json.readValue(raw, GrantSet.class);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt L2 grant entry", e);
        }
    }
}
