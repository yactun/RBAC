package com.example.rbac.model.dto;

import java.util.List;
import java.util.Map;

/**
 * The full effective grant set for a (userId, profileSet) pair. This is the
 * single cached unit shared by L1 (Caffeine) and L2 (Redis), and the value the
 * client derives check/scope/ui from locally. {@code version} is the policy
 * version at write time, used for cheap staleness checks.
 */
public record GrantSet(List<String> permissions,
                       Map<String, Scope> scopes,
                       Map<String, UiElements> ui,
                       long version) {

    public boolean has(String permissionKey) {
        return permissions != null && permissions.contains(permissionKey);
    }
}
