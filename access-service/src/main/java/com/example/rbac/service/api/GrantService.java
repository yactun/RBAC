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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 */
@Service
public class GrantService {

    private static final Logger log = LoggerFactory.getLogger(GrantService.class);

    private final GrantCache cache;
    private final VersionService version;
    private final GrantLoader loader;
    private final RbacResolver resolver = new RbacResolver();
    private final AuditService audit;

    public GrantService(GrantCache cache, VersionService version, GrantLoader loader, AuditService audit) {
        this.cache = cache;
        this.version = version;
        this.loader = loader;
        this.audit = audit;
    }

    public long version() {
        return version.current();
    }

    public GrantSet grants(UserContext user) {
        long current = version.current();

        GrantSet cached = cache.get(user.userId(), user.profiles());
        if (cached != null && cached.version() >= current) {
            return cached;
        }

        GrantSet computed = compute(user, current);
        cache.put(user.userId(), user.profiles(), computed);
        return computed;
    }

    public CheckResult check(UserContext user, String key, String requestId, String service) {
        GrantSet g;
        try {
            g = grants(user);
        } catch (RuntimeException e) {
            log.warn("check failed closed for user={}: {}", user.userId(), e.toString());
            audit.decision(user.userId(), user.profiles(), "check", key, false, 0L, requestId, service);
            return CheckResult.deny(0L);
        }
        boolean allowed = g.has(key);
        audit.decision(user.userId(), user.profiles(), "check", key, allowed, g.version(), requestId, service);
        return new CheckResult(allowed, grantedBy(user, key), g.version());
    }

    private List<String> grantedBy(UserContext user, String key) {
        return loader.load(user.profiles()).stream()
                .filter(g -> g.permissionKeys().contains(key))
                .map(ProfileGrants::profileName)
                .toList();
    }

    public Scope scope(UserContext user, String resourceType) {
        GrantSet g = grants(user);
        Scope s = g.scopes().get(resourceType);
        if (s == null) {
            // A legitimate deny (no scope for this resource type) → 403, not a 500.
            // Still refuses an unscoped query (ADR §9.3 fail-closed).
            throw new AccessDeniedException("No data scope for resourceType=" + resourceType
                    + " (user denied an unscoped query)");
        }
        return s;
    }

    public UiElements ui(UserContext user, String app) {
        GrantSet g = grants(user);
        UiElements ui = g.ui().get(app);
        if (ui == null) {
            // No UI bundle for this app → nothing is visible. A legitimate deny, not
            // an error: return an empty bundle so the front-end renders nothing.
            return new UiElements(app, List.of());
        }
        return ui;
    }

    private GrantSet compute(UserContext user, long version) {
        // On error this THROWS rather than returning an empty set: the caller must
        // not cache a fail-closed result, or one transient DB blip would deny the
        // user for the whole TTL. check() catches the error and denies per request.
        List<ProfileGrants> grants = loader.load(user.profiles());
        RbacResolver.Resolved r = resolver.resolve(grants, null, Set.of());
        return new GrantSet(r.permissions(), r.scopes(), r.ui(), version);
    }
}
