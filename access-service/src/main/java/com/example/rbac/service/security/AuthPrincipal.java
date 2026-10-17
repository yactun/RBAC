package com.example.rbac.service.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * S1 (ADR-002): resolve the <b>verified</b> caller of the current request.
 *
 * <p>When PDP auth is enabled, the {@link SecurityContextHolder} is authoritative
 * (populated by {@link MeshAuthFilter} in {@code mesh} mode or by the OAuth2
 * resource server in {@code jwt} mode) and the {@code X-Actor} header is
 * IGNORED — the audit actor is the identity the trust boundary verified.
 *
 * <p>In disabled (dev/test) mode there is no SecurityContext, so it falls back
 * to the {@code X-Actor} header (default {@code "unknown"}). This keeps local
 * development and the existing test-suite working, and the startup WARN in
 * {@link RbacAuthDisabledWarning} is what stops it being forgotten in prod.
 */
public final class AuthPrincipal {

    private AuthPrincipal() {
    }

    public static CallerIdentity current(HttpServletRequest request) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken)) {
            String name = principalName(a);
            if (name != null && !name.isBlank()) {
                Set<String> authorities = a.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                return new CallerIdentity(name, authorities);
            }
        }
        // Disabled/dev mode (no verified authentication): fall back to the header.
        String x = request.getHeader("X-Actor");
        return new CallerIdentity(x == null || x.isBlank() ? "unknown" : x, Set.of(CallerIdentity.WILDCARD));
    }

    /**
     * The principal name, whichever token type carries it:
     * <ul>
     *   <li>mesh mode — a {@code String} SPIFFE ID;</li>
     *   <li>jwt mode — the token itself ({@link Jwt}); the name is its {@code sub} claim.</li>
     * </ul>
     */
    private static String principalName(Authentication a) {
        Object p = a.getPrincipal();
        if (p instanceof String s) {
            return s;
        }
        if (p instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return null;
    }
}
