package com.example.rbac.service.it;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1 (ADR-002), jwt mode: the full PDP booted with
 * {@code rbac.pdp.auth.enabled=true, mode=jwt}. Calls present a real RSA-signed
 * JWT (client-credentials shape); Spring's OAuth2 resource server validates it.
 *
 * <ul>
 *   <li>no token &rarr; 401;</li>
 *   <li>a token without the admin scope &rarr; 200 on decisions, 403 on admin;</li>
 *   <li>a token with {@code scope=rbac-admin} &rarr; 201 on admin, and the audit
 *       actor is the token <b>subject</b> — not the {@code X-Actor} header.</li>
 * </ul>
 *
 * <p>The {@link JwtDecoder} bean plays the role of the production
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} configuration
 * (same code path: the decoder validates the token). Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rbac.pdp.auth.enabled=true",
        "rbac.pdp.auth.mode=jwt",
        "rbac.pdp.auth.admin-authority=rbac-admin"
})
class AccessServiceAuthJwtTest {

    private static final RSAKey KEY;
    private static final RSASSASigner SIGNER;

    static {
        try {
            RSAKey k = new RSAKeyGenerator(2048).keyID("rbac-test").generate();
            KEY = k;
            SIGNER = new RSASSASigner(k.toRSAPrivateKey());
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("failed to generate the test RSA key", e);
        }
    }

    /** Sign a client-credentials-style token; optionally with scopes. */
    private static String token(String subject, String... scopes) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .expirationTime(Date.from(Instant.now().plus(10, ChronoUnit.MINUTES)));
        if (scopes.length > 0) {
            claims.claim("scope", List.of(scopes));
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        try {
            jwt.sign(SIGNER);
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("failed to sign the test token", e);
        }
        return jwt.serialize();
    }

    @TestConfiguration
    static class TestDecoder {
        @Bean
        JwtDecoder jwtDecoder() {
            try {
                return NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            } catch (com.nimbusds.jose.JOSEException e) {
                throw new IllegalStateException("failed to build the test JwtDecoder", e);
            }
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("access")
            .withUsername("access")
            .withPassword("access");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.12-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    @Test
    void noTokenIsUnauthorized() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);

        assertThat(rest.exchange("/v1/grants", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", "u-1", "profiles", Set.of("ops")), h), Void.class)
                .getStatusCode())
                .as("decision endpoint without a token").isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(rest.exchange("/v1/admin/profiles", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "p-1"), h), Void.class)
                .getStatusCode())
                .as("admin endpoint without a token").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTokenWithoutTheAdminScope_canDecide_butNotAuthor() {
        HttpHeaders h = bearer(token("orders-svc")); // no scope claim

        assertThat(rest.exchange("/v1/grants", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", "u-1", "profiles", Set.of("ops")), h), Map.class)
                .getStatusCode())
                .as("decision for a valid (non-admin) token").isEqualTo(HttpStatus.OK);

        assertThat(rest.exchange("/v1/admin/profiles", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "p-1"), h), Void.class)
                .getStatusCode())
                .as("admin for a token lacking the rbac-admin scope").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aTokenWithTheAdminScope_canAuthor_andTheActorIsTheSubject_notTheHeader() {
        HttpHeaders h = bearer(token("rbac-console", "rbac-admin"));
        h.add("X-Actor", "spoofed-actor"); // must be IGNORED (S1 invariant)

        assertThat(rest.exchange("/v1/admin/profiles", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "auth-jwt-profile"), h), Map.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        String actor = jdbc.queryForObject(
                "select actor from audit_change where entity_ref = 'auth-jwt-profile' "
                        + "order by id desc limit 1",
                String.class);
        assertThat(actor)
                .as("the audit actor is the token subject, not the X-Actor header")
                .isEqualTo("rbac-console");
    }
}
