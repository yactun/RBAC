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

import java.time.Duration;
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
 *
 * <p>Metrics flow through the {@link RbacClientMetrics} sink (no-op when the host
 * app has no Micrometer registry — see the auto-configuration).
 */
public class DefaultAccessClient implements AccessClient, InvalidationHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultAccessClient.class);

    private final L1Cache l1;
    private final L2Cache l2; // nullable — absent when the host app has no Redis
    private final RestClient rest;
    private final ObjectMapper json;
    private final RbacClientMetrics metrics;
    private final AtomicLong localVersion = new AtomicLong(0);

    public DefaultAccessClient(L1Cache l1, L2Cache l2, RestClient rest, ObjectMapper json) {
        this(l1, l2, rest, json, RbacClientMetrics.NONE);
    }

    public DefaultAccessClient(L1Cache l1, L2Cache l2, RestClient rest, ObjectMapper json,
                               RbacClientMetrics metrics) {
        this.l1 = l1;
        this.l2 = l2;
        this.rest = rest;
        this.json = json;
        this.metrics = (metrics == null) ? RbacClientMetrics.NONE : metrics;
    }

    @Override
    public CheckResult check(UserContext user, String key) {
        long start = System.nanoTime();
        String source = "error";
        try {
            Resolved r = resolve(user);
            source = r.source();
            boolean allowed = r.set().has(key);
            // grantedBy is part of the cached GrantSet (REVIEW D2) — provenance is
            // derived locally, like everything else (ADR D9).
            return new CheckResult(allowed, r.set().grantedBy(key), r.set().version());
        } catch (RuntimeException e) {
            log.warn("check failed closed for user={}: {}", user.userId(), e.toString());
            return CheckResult.deny(localVersion.get());
        } finally {
            metrics.decision("check", source, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    @Override
    public Scope scope(UserContext user, String resourceType) {
        long start = System.nanoTime();
        String source = "error";
        try {
            // fail-closed: a genuine fault throws here → 500
            Resolved r = resolve(user);
            source = r.source();
            Scope s = (r.set().scopes() == null) ? null : r.set().scopes().get(resourceType);
            if (s == null) {
                // A legitimate deny (no scope for this resource type) → 403, not a 500.
                // Still refuses an unscoped query (ADR §9.3 fail-closed).
                throw new AccessDeniedException("No data scope for resourceType=" + resourceType
                        + " (user denied an unscoped query)");
            }
            return s;
        } finally {
            metrics.decision("scope", source, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    @Override
    public UiElements ui(UserContext user, String app) {
        long start = System.nanoTime();
        String source = "error";
        try {
            // fail-closed: a genuine fault throws here → 500
            Resolved r = resolve(user);
            source = r.source();
            UiElements ui = (r.set().ui() == null) ? null : r.set().ui().get(app);
            if (ui == null) {
                // No UI bundle for this app → nothing is visible. A legitimate deny, not
                // an error: return an empty bundle so the front-end renders nothing.
                return new UiElements(app, List.of());
            }
            return ui;
        } finally {
            metrics.decision("ui", source, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    @Override
    public GrantSet grants(UserContext user) {
        long start = System.nanoTime();
        String source = "error";
        try {
            Resolved r = resolve(user);
            source = r.source();
            return r.set();
        } finally {
            metrics.decision("grants", source, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /**
     * L1 → L2 → PDP resolution of the single cached unit (ADR D5/D9). Records the
     * L1/L2 hit-miss and PDP-fetch counters; the caller records the decision timer
     * with the {@link Resolved#source()} tag.
     */
    private Resolved resolve(UserContext user) {
        long current = localVersion.get();

        // D1 (REVIEW): the GrantSet is a pure function of the profile set (pure RBAC,
        // ADR D1/D2) — the cache is keyed on the profile set, not the user. userId is
        // still sent to the PDP for AUDIT; it is deliberately excluded from the key.
        GrantSet g = l1.get(user.profiles());
        if (g != null && g.version() >= current) {
            metrics.cache("l1", "hit");
            return new Resolved(g, "l1");
        }
        metrics.cache("l1", "miss");

        if (l2 != null) {
            try {
                g = l2.get(user.profiles());
                if (g != null && g.version() >= current) {
                    l1.put(user.profiles(), g);
                    metrics.cache("l2", "hit");
                    return new Resolved(g, "l2");
                }
                metrics.cache("l2", "miss");
            } catch (RuntimeException e) {
                // L2 (Redis) is down. Redis is a CACHE, not a hard dependency (REVIEW #5):
                // bypass this tier and fall through to the PDP instead of failing the whole
                // decision. The PDP still serves correct grants from Postgres.
                log.warn("L2 (Redis) read failed; falling through to the PDP: {}", e.toString());
                metrics.cache("l2", "error");
            }
        }

        // C5b (REVIEW #10): coalesce concurrent L1 misses for the same key into ONE
        // PDP fetch — Caffeine's per-key locking runs the loader once and hands the
        // result to every waiter. A loader failure stores nothing and propagates to
        // all waiters (fail-closed preserved).
        try {
            g = l1.getOrLoad(user.profiles(), () -> {
                GrantSet fetched = fetchFromPdp(user);
                metrics.pdpFetch(true); // inside the loader: counted once per real fetch
                return fetched;
            });
        } catch (RuntimeException e) {
            metrics.pdpFetch(false);
            throw e;
        }
        return new Resolved(g, "pdp");
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

    /**
     * C2 (REVIEW #6): seed/refresh the local policy version from the PDP's lightweight
     * {@code GET /v1/version} probe. A fresh replica starts at version 0 and would
     * otherwise accept a stale L2 entry (written before its first invalidation event);
     * seeding the real version makes the existing {@code g.version() >= localVersion}
     * check reject it and refetch. Monotonic (max), and fail-soft: if the PDP is
     * unreachable the current version is kept (TTL remains the safety net).
     */
    public void syncVersion() {
        try {
            Map<String, Object> body = rest.get().uri("/v1/version").retrieve().body(Map.class);
            Object v = (body == null) ? null : body.get("version");
            if (v instanceof Number n) {
                localVersion.accumulateAndGet(n.longValue(), Math::max);
            }
        } catch (RuntimeException e) {
            log.warn("Version sync from PDP failed; keeping local version: {}", e.toString());
        }
    }

    @Override
    public void onBusConnected() {
        // The bus just (re)established: any events missed while it was down are
        // covered by re-seeding the local version from the PDP (fail-soft).
        syncVersion();
    }

    /** The resolved grant set plus the tier that served it (l1|l2|pdp), for the decision metric. */
    private record Resolved(GrantSet set, String source) { }
}
