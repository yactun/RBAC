package com.example.rbac.service.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1 (ADR-002): the audit actor is the <b>verified principal</b>. When a real
 * authentication is in the SecurityContext (mesh or jwt mode) it wins — even if
 * a client also sent an {@code X-Actor} header. Only in disabled/dev mode
 * (no real authentication) does {@code X-Actor} apply.
 */
class AuthPrincipalTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theVerifiedPrincipalWins_overASpoofedXActorHeader() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "spiffe://cluster.local/ns/prod/sa/orders-svc", null,
                List.of(new SimpleGrantedAuthority("rbac-admin"))));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Actor", "spoofed-actor"); // must be IGNORED

        CallerIdentity id = AuthPrincipal.current(req);

        assertThat(id.principal()).isEqualTo("spiffe://cluster.local/ns/prod/sa/orders-svc");
        assertThat(id.hasAuthority("rbac-admin")).isTrue();
        assertThat(id.hasAuthority("something-else")).isFalse();
    }

    @Test
    void anonymousFallsBackToTheXActorHeader() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("n", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Actor", "it-qa");

        CallerIdentity id = AuthPrincipal.current(req);

        assertThat(id.principal()).isEqualTo("it-qa");
        assertThat(id.hasAuthority("anything")).isTrue(); // dev-mode wildcard
    }

    @Test
    void noContextAndNoHeaderDefaultsToUnknown() {
        CallerIdentity id = AuthPrincipal.current(new MockHttpServletRequest());
        assertThat(id.principal()).isEqualTo("unknown");
        assertThat(id.hasAuthority("anything")).isTrue();
    }
}
