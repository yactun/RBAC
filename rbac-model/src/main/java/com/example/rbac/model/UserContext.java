package com.example.rbac.model;

import java.util.Set;

/**
 * The identity the client has already established with the IDM.
 * The PDP never talks to the IDM: the client validates the user token,
 * extracts the profile set, and hands it over here (design A1).
 */
public record UserContext(String userId, Set<String> profiles) {

    public UserContext {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        profiles = (profiles == null) ? Set.of() : Set.copyOf(profiles);
    }
}
