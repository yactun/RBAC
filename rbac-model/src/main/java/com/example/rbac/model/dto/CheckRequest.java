package com.example.rbac.model.dto;

import com.example.rbac.model.UserContext;

import java.util.Set;

/** Request body for {@code POST /v1/check}. */
public record CheckRequest(UserContext user, String key) {

    public CheckRequest {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key is required");
        }
    }

    public CheckRequest(String userId, Set<String> profiles, String key) {
        this(new UserContext(userId, profiles), key);
    }
}
