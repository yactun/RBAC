package com.example.rbac.service.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1 (ADR-002): pins the mesh-mode identity resolution — the SPIFFE ID from the
 * mesh's {@code Forwarded-Client-Cert} becomes the principal, admin principals
 * get the admin authority, and a missing header is anonymous (→ 401 upstream).
 */
class MeshAuthFilterTest {

    private static final String ADMIN = "spiffe://cluster.local/ns/prod/sa/rbac-console";

    private final MeshAuthFilter filter = new MeshAuthFilter("rbac-admin", Set.of(ADMIN));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Authentication run(String fpcHeader) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (fpcHeader != null) {
            req.addHeader("Forwarded-Client-Cert", fpcHeader);
        }
        Authentication[] holder = new Authentication[1];
        FilterChain chain = (r, s) -> holder[0] = SecurityContextHolder.getContext().getAuthentication();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        return holder[0];
    }

    @Test
    void theSpiffeIdFromTheFpcHeaderBecomesThePrincipal() throws Exception {
        Authentication a = run("San=\"spiffe://cluster.local/ns/prod/sa/orders-svc\";CertChain=abc");
        assertThat(a.getName()).isEqualTo("spiffe://cluster.local/ns/prod/sa/orders-svc");
        assertThat(a.isAuthenticated()).isTrue();
        assertThat(a.getAuthorities()).isEmpty(); // a decision caller, not an admin
    }

    @Test
    void anAdminPrincipalGetsTheAdminAuthority() throws Exception {
        Authentication a = run("San=\"" + ADMIN + "\";CertChain=abc");
        assertThat(a.getAuthorities()).extracting("authority").contains("rbac-admin");
    }

    @Test
    void aNonAdminPrincipalDoesNotGetTheAdminAuthority() throws Exception {
        Authentication a = run("San=\"spiffe://cluster.local/ns/prod/sa/orders-svc\";CertChain=abc");
        assertThat(a.getAuthorities()).extracting("authority").doesNotContain("rbac-admin");
    }

    @Test
    void aMissingFpcHeaderIsAnonymous() throws Exception {
        Authentication a = run(null);
        assertThat(a).isInstanceOf(AnonymousAuthenticationToken.class);
    }

    @Test
    void parseSan_handlesTheIstioFormat() {
        assertThat(MeshAuthFilter.parseSan("San=\"spiffe://x/y\";CertChain=zz"))
                .isEqualTo("spiffe://x/y");
        assertThat(MeshAuthFilter.parseSan(null)).isNull();
        assertThat(MeshAuthFilter.parseSan("")).isNull();
        assertThat(MeshAuthFilter.parseSan("CertChain=zz")).isNull(); // no San entry
    }
}
