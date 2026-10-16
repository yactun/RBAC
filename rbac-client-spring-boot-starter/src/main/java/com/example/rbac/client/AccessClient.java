package com.example.rbac.client;

import com.example.rbac.model.UserContext;
import com.example.rbac.model.dto.CheckResult;
import com.example.rbac.model.dto.GrantSet;
import com.example.rbac.model.dto.Scope;
import com.example.rbac.model.dto.UiElements;

/**
 * The single entry point a microservice or front-end codes against.
 * All methods are fail-closed: on error, {@code check} denies and
 * {@code scope}/{@code ui} throw, so an unscoped query can never run (ADR §9.3).
 */
public interface AccessClient {

    /** Feature gate: may this user do {@code key}? */
    CheckResult check(UserContext user, String key);

    /** Convenience boolean over {@link #check}. */
    default boolean has(UserContext user, String key) {
        return check(user, key).allowed();
    }

    /**
     * Data scope for a resource type; throws if the user has no scope (fail-closed).
     *
     * <p>The returned {@link Scope} is the canonical Option A filter list (ADR-002 S2):
     * <pre>{@code
     * Scope s = accessClient.scope(user, "orders");          // throws if unspecced/denied
     * for (Scope.Filter f : s.filters()) {
     *     switch (f.op()) {
     *         case ALL  -> { /* no condition on f.dimension() (all rows) *\/ }
     *         case IN   -> { if (f.values().isEmpty()) return noRows; query = query.in(f.dimension(), f.values()); }
     *         case EQ   -> query = query.eq(f.dimension(), f.single());
     *         case GT   -> query = query.gt(f.dimension(), f.single());
     *         // GTE / LT / LTE / BETWEEN (f.low(), f.high()) analogous
     *     }
     * }
     * }</pre>
     * Fail-closed: {@code s.values("orgIds")} (IN convenience) and {@code s.isAll(dim)}
     * <b>throw</b> for a dimension the scope does not declare — a typo can never become
     * "no filter = all rows". The developer owns the final mapping onto its ORM/SQL.
     */
    Scope scope(UserContext user, String resourceType);

    /** UI visibility bundle for an app; throws if the user has no bundle for it. */
    UiElements ui(UserContext user, String app);

    /** The full cached grant set (the shared unit, ADR D9). */
    GrantSet grants(UserContext user);
}
