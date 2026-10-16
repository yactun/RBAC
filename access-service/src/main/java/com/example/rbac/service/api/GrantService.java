package com.example.rbac.service.api;

import com.example.rbac.model.AccessDeniedException;
import com.example.rbac.model.UserContext;
import com.example.rbac.model.dto.CheckResult;
import com.example.rbac.model.dto.GrantSet;
import com.example.rbac.model.dto.Scope;
import com.example.rbac.model.dto.UiElements;
import com.example.rbac.service.audit.AuditService;
import com.example.rbac.service.cache.GrantCache;
import com.example.rbac.service.cache.VersionService;
import com.example.rbac.service.rbac.GrantLoader;
import com.example.rbac.service.rbac.ProfileGrants;
import com.example.rbac.service.rbac.RbacResolver;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The decision path (ADR D9, D10):
 * <ol>
 *   <li>Read L2 (Redis). On hit with a current version, return immediately.</li>
 *   <li>On miss, load grants, resolve, write L2 with the current version.</li>
 * </ol>
 * Fail-closed: an error during resolution <b>propagates</b> (it is never cached, so a
 * transient blip can't poison the cache) — {@code check()} catches it and denies per
 * request; a genuine fault surfaces as a 500. A user who simply has no grant for a
 * scope/resource is a 403 ({@link AccessDeniedException}), not a 500.
 *
 * <p>Metrics (SLO evidence): {@code rbac.pdp.decision} timer per operation and
 * {@code rbac.pdp.cache} hit/miss counters for the shared L2 tier.
 */
@Service
public class GrantService {

    private static final Logger log = LoggerFactory.getLogger(GrantService.class);

    private final GrantCache cache;
    private final VersionService version;
    private final GrantLoader loader;
    private final RbacResolver resolver = new RbacResolver();
    private final AuditService audit;
    private final MeterRegistry metrics;

    public GrantService(GrantCache cache, VersionService version, GrantLoader loader,
                        AuditService audit, MeterRegistry metrics) {
        this.cache = cache;
        this.version = version;
        this.loader = loader;
        this.audit = audit;
        this.metrics = metrics;
    }

    public long version() {
        return version.current();
    }

    public GrantSet grants(UserContext user) {
        long start = System.nanoTime();
        try {
            return resolveGrants(user);
        } finally {
            recordDecision("grants", start);
        }
    }

    public CheckResult check(UserContext user, String key, String requestId, String service) {
        long start = System.nanoTime();
        try {
            GrantSet g = resolveGrants(user);
            boolean allowed = g.has(key);
            audit.decision(user.userId(), user.profiles(), "check", key, allowed, g.version(), requestId, service);
            return new CheckResult(allowed, grantedBy(user, key), g.version());
        } catch (RuntimeException e) {
            // Fail-closed (ADR §9.3): a fault on the decision path is a deny, never an error.
            log.warn("check failed closed for user={}: {}", user.userId(), e.toString());
            audit.decision(user.userId(), user.profiles(), "check", key, false, 0L, requestId, service);
            return CheckResult.deny(0L);
        } finally {
            recordDecision("check", start);
        }
    }

    private List<String> grantedBy(UserContext user, String key) {
        return loader.load(user.profiles()).stream()
                .filter(g -> g.permissionKeys().contains(key))
                .map(ProfileGrants::profileName)
                .toList();
    }

    public Scope scope(UserContext user, String resourceType) {
        long start = System.nanoTime();
        try {
            GrantSet g = resolveGrants(user);
            Scope s = (g.scopes() == null) ? null : g.scopes().get(resourceType);
            if (s == null) {
                // A legitimate deny (no scope for this resource type) → 403, not a 500.
                // Still refuses an unscoped query (ADR §9.3 fail-closed).
                throw new AccessDeniedException("No data scope for resourceType=" + resourceType
                        + " (user denied an unscoped query)");
            }
            return s;
        } finally {
            recordDecision("scope", start);
        }
    }

    public UiElements ui(UserContext user, String app) {
        long start = System.nanoTime();
        try {
            GrantSet g = resolveGrants(user);
            UiElements ui = (g.ui() == null) ? null : g.ui().get(app);
            if (ui == null) {
                // No UI bundle for this app → nothing is visible. A legitimate deny, not
                // an error: return an empty bundle so the front-end renders nothing.
                return new UiElements(app, List.of());
            }
            return ui;
        } finally {
            recordDecision("ui", start);
        }
    }

    /**
     * The single cached unit (ADR D9): L2 hit with a current version, else compute,
     * write L2, return. Records the L2 hit/miss counters. On compute failure it
     * THROWS rather than returning an empty set: the caller must not cache a
     * fail-closed result, or one transient DB blip would deny the user for the TTL.
     */
    private GrantSet resolveGrants(UserContext user) {
        // Redis is a CACHE, not a hard dependency (REVIEW #5): if it is down we must still
        // serve correct grants from Postgres. Each Redis touch is guarded so a Redis outage
        // degrades to "compute from Postgres, uncached" instead of a 500. A floor version (0)
        // is stamped only when we could not read the real one — the PDP is authoritative, so a
        // client accepts a Postgres-computed result regardless of the stamp.
        long current;
        boolean redisUp = true;
        try {
            current = version.current();
        } catch (RuntimeException e) {
            log.warn("Redis unavailable (version.current); serving from Postgres, degraded: {}", e.toString());
            current = 0L;
            redisUp = false;
        }

        GrantSet cached = null;
        if (redisUp) {
            try {
                cached = cache.get(user.userId(), user.profiles());
            } catch (RuntimeException e) {
                log.warn("Redis unavailable (cache.get); serving from Postgres, degraded: {}", e.toString());
                redisUp = false;
            }
        }
        if (cached != null && cached.version() >= current) {
            metrics.counter("rbac.pdp.cache", "tier", "l2", "result", "hit").increment();
            return cached;
        }
        metrics.counter("rbac.pdp.cache", "tier", "l2", "result", "miss").increment();

        GrantSet computed = compute(user, current);
        if (redisUp) {
            try {
                cache.put(user.userId(), user.profiles(), computed);
            } catch (RuntimeException e) {
                log.warn("Redis unavailable (cache.put); returning the uncached Postgres result: {}", e.toString());
            }
        }
        return computed;
    }

    private GrantSet compute(UserContext user, long version) {
        List<ProfileGrants> grants = loader.load(user.profiles());
        RbacResolver.Resolved r = resolver.resolve(grants, null, Set.of());
        return new GrantSet(r.permissions(), r.scopes(), r.ui(), version);
    }

    private void recordDecision(String operation, long startNanos) {
        metrics.timer("rbac.pdp.decision", "operation", operation)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
