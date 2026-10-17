package com.example.rbac.service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S1 (ADR-002), {@code mesh} mode: the service mesh (Istio, Linkerd, Cilium…)
 * terminates mTLS and conveys the verified client identity in the
 * {@code Forwarded-Client-Cert} header, whose {@code San} entry is the
 * workload's SPIFFE ID, e.g.
 *
 * <pre>
 * Forwarded-Client-Cert: San="spiffe://cluster.local/ns/prod/sa/orders-svc";CertChain=…
 * </pre>
 *
 * <p>The mesh is the trust boundary (mTLS is enforced at the edge), so the
 * app trusts this header — and <b>requires</b> it: no {@code San} ⇒ anonymous
 * ⇒ 401 from the security chain. Admin authority is granted to the principals
 * listed in {@code rbac.pdp.auth.admin-principals} (SPIFFE IDs); everyone
 * else is a plain decision caller. An empty list means <b>no one</b> may call
 * {@code /v1/admin/**} (fail-closed).
 */
public class MeshAuthFilter extends OncePerRequestFilter {

    /** Istio's header carries the SPIFFE ID as {@code San="spiffe://…"}. */
    private static final Pattern SAN = Pattern.compile("San=\"([^\"]+)\"");

    private final String adminAuthority;
    private final Set<String> adminPrincipals;

    public MeshAuthFilter(String adminAuthority, Set<String> adminPrincipals) {
        this.adminAuthority = adminAuthority;
        this.adminPrincipals = adminPrincipals == null ? Set.of() : Set.copyOf(adminPrincipals);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
        String san = parseSan(request.getHeader("Forwarded-Client-Cert"));
        SecurityContextHolder.getContext().setAuthentication(
                san == null
                        ? // ROLE_ANONYMOUS is the standard anonymous authority (same as
                        // Spring's own AnonymousAuthenticationFilter); the token requires
                        // a non-empty authority list.
                        new AnonymousAuthenticationToken("none", "anonymousUser",
                                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
                        : new UsernamePasswordAuthenticationToken(
                                san, null,
                                adminPrincipals.contains(san)
                                        ? List.of(new SimpleGrantedAuthority(adminAuthority))
                                        : List.of()));
        chain.doFilter(request, response);
    }

    static String parseSan(String forwardedClientCert) {
        if (forwardedClientCert == null || forwardedClientCert.isBlank()) {
            return null;
        }
        Matcher m = SAN.matcher(forwardedClientCert);
        return m.find() ? m.group(1) : null;
    }
}
