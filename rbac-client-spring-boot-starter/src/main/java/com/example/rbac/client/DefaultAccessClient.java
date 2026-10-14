package com.example.rbac.client;

import com.example.rbac.client.cache.L1Cache;
import com.example.rbac.client.cache.L2Cache;
import com.example.rbac.client.invalidation.InvalidationHandler;
import com.example.rbac.model.AccessDeniedException;
import com.example.rbac.model.UserContext;
import com.example.rbac.model.dto.CheckResult;
import com.example.rbac.model.dto.GrantSet;
import com.example.rbac.model.dto.InvalidationEvent;
import com.example.rbac.model.dto.Scope;
import com.example.rbac.model.dto.UiElements;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default {@link AccessClient}: L1 (Caffeine) → L2 (Redis, read-only) → PDP (REST).
 * Every tier stores the same {@link GrantSet}; check/scope/ui are derived locally
 * (ADR D9). Fail-closed: {@code check} denies on error, and a genuine fault surfaces
 * as an exception (→ 500). A user who simply lacks the grant is a 403: {@code scope}
 * throws {@link AccessDeniedException} (refusing an unscoped query, ADR §9.3) and
 * {@code ui} returns an empty bundle (nothing visible) instead of throwing.
 *
 * <p>Also implements {@link InvalidationHandler}: a bus event raises the local
 * policy version and clears L1, so the next read sees the new version and
 * refetches (ADR D6, D10).
 */
public class DefaultAccessClient implements AccessClient, InvalidationHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultAccessClient.class);

    private final L1Cache l1;
    private final L2Cache l2; // nullable — absent when the host app has no Redis
    private final RestClient rest;
    private final ObjectMapper json;
    private final AtomicLong localVersion = new AtomicLong(0);

    public DefaultAccessClient(L1Cache l1, L2Cache l2, RestClient rest, ObjectMapper json) {
        this.l1 = l1;
        this.l2 = l2;
        this.rest = rest;
        this.json = json;
    }

    @Override
    public CheckResult check(UserContext user, String key) {
        try {
            GrantSet g = grants(user);
            boolean allowed = g.has(key);
            return new CheckResult(allowed, List.of(), g.version());
        } catch (RuntimeException e) {
            log.warn("check failed closed for user={}: {}", user.userId(), e.toString());
            return CheckResult.deny(localVersion.get());
        }
    }

    @Override
    public Scope scope(UserContext user, String resourceType) {
        GrantSet g = grants(user); // fail-closed: a genuine fault throws here → 500
        Scope s = (g.scopes() == null) ? null : g.scopes().get(resourceType);
        if (s == null) {
            // A legitimate deny (no scope for this resource type) → 403, not a 500.
            // Still refuses an unscoped query (ADR §9.3 fail-closed).
            throw new AccessDeniedException("No data scope for resourceType=" + resourceType
                    + " (user denied an unscoped query)");
        }
        return s;
    }

    @Override
    public UiElements ui(UserContext user, String app) {
        GrantSet g = grants(user); // fail-closed: a genuine fault throws here → 500
        UiElements ui = (g.ui() == null) ? null : g.ui().get(app);
        if (ui == null) {
            // No UI bundle for this app → nothing is visible. A legitimate deny, not
            // an error: return an empty bundle so the front-end renders nothing.
            return new UiElements(app, List.of());
        }
        return ui;
    }

    @Override
    public GrantSet grants(UserContext user) {
        long current = localVersion.get();

        GrantSet g = l1.get(user.userId(), user.profiles());
        if (g != null && g.version() >= current) {
            return g;
        }
        if (l2 != null) {
            g = l2.get(user.userId(), user.profiles());
            if (g != null && g.version() >= current) {
                l1.put(user.userId(), user.profiles(), g);
                return g;
            }
        }
        g = fetchFromPdp(user);
        l1.put(user.userId(), user.profiles(), g);
        return g;
    }

    private GrantSet fetchFromPdp(UserContext user) {
        Map<String, Object> body = Map.of("userId", user.userId(), "profiles", user.profiles());
        String raw = rest.post()
                .uri("/v1/grants")
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            return json.readValue(raw, GrantSet.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse grant set from PDP", e);
        }
    }

    // --- invalidation (ADR D6, D10) ---

    @Override
    public void onEvent(InvalidationEvent event) {
        localVersion.accumulateAndGet(event.version(), Math::max);
        // The event does not carry the full profile set, so we cannot evict a
        // precise key; clearing L1 is the safe, simple choice (L1 is small and
        // per-replica). The next read refetches and revalidates the version.
        l1.clear();
    }
}
