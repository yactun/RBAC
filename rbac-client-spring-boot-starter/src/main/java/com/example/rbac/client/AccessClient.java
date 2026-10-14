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

    /** Data scope for a resource type; throws if the user has no scope (fail-closed). */
    Scope scope(UserContext user, String resourceType);

    /** UI visibility bundle for an app; throws if the user has no bundle for it. */
    UiElements ui(UserContext user, String app);

    /** The full cached grant set (the shared unit, ADR D9). */
    GrantSet grants(UserContext user);
}
