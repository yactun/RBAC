package com.example.rbac.service.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Set;

/**
 * S1 (ADR-002): the PDP's authentication/authorization chain. The SECURE chain
 * is active when {@code rbac.pdp.auth.enabled=true}; otherwise an OPEN chain
 * keeps the app fully available (dev/test default).
 *
 * <ul>
 *   <li>{@code mode=mesh} — identity from the mesh's {@code Forwarded-Client-Cert}
 *       (see {@link MeshAuthFilter}); no token machinery needed.</li>
 *   <li>{@code mode=jwt} — OAuth2 client-credentials / OIDC bearer tokens,
 *       validated by Spring's resource server (configure
 *       {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} — or provide
 *       a {@code JwtDecoder} bean). The admin authority is the configured
 *       {@code rbac.pdp.auth.admin-authority} (default {@code rbac-admin}),
 *       expected in the token's {@code scope}/{@code scp} or {@code roles} claim.</li>
 * </ul>
 *
 * <p>Authorization: {@code /v1/admin/**} requires the admin authority (a 403
 * for an authenticated non-admin); every other {@code /v1/**} endpoint requires
 * an authenticated caller (401 otherwise). Actuator health/info stay open for
 * the platform probes.
 *
 * <p>The {@code X-Actor} header has NO effect while this is active — the audit
 * actor is the verified principal ({@link AuthPrincipal#current}).
 *
 * <p>A {@link SecurityFilterChain} bean ALWAYS exists (secure when enabled, open
 * when disabled): that keeps {@code @ConditionalOnDefaultWebSecurity} from ever
 * matching, so neither Spring Boot's default form-login chain nor the actuator's
 * {@code ManagementWebSecurityAutoConfiguration} (which would require HttpSecurity
 * and secure the actuator) can take over. Disabled mode is therefore exactly as
 * open as the app was before S1 (dev/test default; the startup WARN in
 * {@link RbacAuthDisabledWarning} stops it being forgotten in prod).
 */
@Configuration
public class RbacSecurityConfig {

    /** Secure chain: mesh (or jwt) authentication + admin authority split. */
    @Bean
    @ConditionalOnProperty(prefix = "rbac.pdp.auth", name = "enabled", havingValue = "true")
    public SecurityFilterChain rbacSecurityFilterChain(
            HttpSecurity http,
            ObjectProvider<MeshAuthFilter> meshFilter,
            @Value("${rbac.pdp.auth.mode:jwt}") String mode,
            @Value("${rbac.pdp.auth.admin-authority:rbac-admin}") String adminAuthority) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) ->
                                res.sendError(HttpStatus.UNAUTHORIZED.value()))
                        .accessDeniedHandler((req, res, ex) ->
                                res.sendError(HttpStatus.FORBIDDEN.value())))
                .authorizeHttpRequests(a -> a
                        // /error must be open: res.sendError(401/403) triggers a servlet ERROR
                        // dispatch to /error that re-enters this chain WITHOUT the original
                        // identity (mesh FPC header / JWT), so it would be re-authenticated as
                        // anonymous and its own 401 would override the intended 403. Permitting
                        // it lets the original error status render unchanged.
                        .requestMatchers("/error", "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v1/admin/**").hasAuthority(adminAuthority)
                        .anyRequest().authenticated());
        if ("mesh".equalsIgnoreCase(mode)) {
            MeshAuthFilter mesh = meshFilter.getIfAvailable();
            if (mesh != null) {
                http.addFilterBefore(mesh, UsernamePasswordAuthenticationFilter.class);
            }
        } else if ("jwt".equalsIgnoreCase(mode)) {
            // Decoder from spring.security.oauth2.resourceserver.jwt.* or a JwtDecoder bean;
            // without one the context fails fast (fail-closed: no token, no access).
            //
            // The converter maps the token's scope/scp claim values to PLAIN authorities
            // (scope=rbac-admin → "rbac-admin"), so a client-credentials token carrying
            // scope "rbac-admin" satisfies hasAuthority(rbac.pdp.auth.admin-authority).
            JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
            scopes.setAuthorityPrefix("");
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(scopes);
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        }
        return http.build();
    }

    /**
     * Open chain (default, disabled/dev mode): no authentication, everything
     * permitted — identical in effect to the pre-S1 "no security" app. Present
     * precisely so the default-web-security auto-configurations back off (see
     * the class Javadoc).
     */
    @Bean
    @ConditionalOnProperty(prefix = "rbac.pdp.auth", name = "enabled", havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain rbacOpenSecurityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(sc -> sc.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "rbac.pdp.auth", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "rbac.pdp.auth", name = "mode", havingValue = "mesh")
    public MeshAuthFilter meshAuthFilter(
            @Value("${rbac.pdp.auth.admin-authority:rbac-admin}") String adminAuthority,
            @Value("${rbac.pdp.auth.admin-principals:}") Set<String> adminPrincipals) {
        return new MeshAuthFilter(adminAuthority, adminPrincipals);
    }
}


