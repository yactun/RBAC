package com.example.rbac.model.dto;

import java.util.List;
import java.util.Map;

/**
 * The full effective grant set for a profile set. This is the single cached unit
 * shared by L1 (Caffeine) and L2 (Redis), and the value the client derives
 * check/scope/ui from locally. {@code version} is the policy version at write
 * time, used for cheap staleness checks.
 *
 * <p>{@code grantedBy} maps each permission key to the profiles that grant it —
 * provenance for {@code POST /v1/check}'s {@code grantedBy} field. It is part of
 * the cached unit (REVIEW D2) so the PDP answers {@code grantedBy} from the cache
 * instead of a per-check DB query. Still a pure function of the profile set (pure
 * RBAC) — no {@code userId}.
 */
public record GrantSet(List<String> permissions,
                       Map<String, Scope> scopes,
                       Map<String, UiElements> ui,
                       Map<String, List<String>> grantedBy,
                       long version) {

    public boolean has(String permissionKey) {
        return permissions != null && permissions.contains(permissionKey);
    }

    /** The profiles granting {@code key} (empty when none — see {@link #has}). */
    public List<String> grantedBy(String permissionKey) {
        return (grantedBy == null) ? List.of() : grantedBy.getOrDefault(permissionKey, List.of());
    }
}
