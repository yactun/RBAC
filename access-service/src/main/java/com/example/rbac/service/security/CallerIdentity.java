package com.example.rbac.service.security;

import java.util.Set;

/**
 * S1 (ADR-002): a verified caller — the principal (WHO) and its authorities
 * (WHAT it may do). The principal comes from the trust boundary (the service
 * mesh's {@code Forwarded-Client-Cert} in {@code mesh} mode, or the OAuth2/OIDC
 * token's subject in {@code jwt} mode) — <b>never</b> from a client-supplied
 * header when auth is enabled.
 */
public record CallerIdentity(String principal, Set<String> authorities) {

    /** The dev-mode wildcard authority (auth disabled: everything is allowed). */
    public static final String WILDCARD = "*";

    public CallerIdentity {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("principal must not be blank");
        }
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
    }

    public boolean hasAuthority(String authority) {
        return authorities.contains(WILDCARD) || authorities.contains(authority);
    }
}
