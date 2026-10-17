package com.example.rbac.client.security;

/**
 * S1 (ADR-002): supplies the bearer token the client presents to the PDP when
 * the PDP runs in {@code rbac.pdp.auth.mode=jwt} (OAuth2 client-credentials /
 * OIDC). Not needed for {@code mode=mesh} (the mesh conveys identity) or a
 * disabled-auth PDP.
 *
 * <p>Provide a bean (e.g. wrapping Spring's {@code ClientHttpRequestInterceptor}
 * -style token acquisition, a Vault lookup, or a cached client-credentials
 * exchange) and the starter attaches it to every PDP call. Or simply set
 * {@code rbac.client.pdp-token} for a static token (dev/test). Returning
 * {@code null}/blank sends no {@code Authorization} header.
 */
@FunctionalInterface
public interface BearerTokenProvider {

    /** The token (without or with the {@code "Bearer "} prefix — both are handled). */
    String bearerToken();
}
