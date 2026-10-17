package com.example.rbac.service.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1 (ADR-002), mesh mode: the full PDP booted with
 * {@code rbac.pdp.auth.enabled=true, mode=mesh}. Identity comes from the mesh's
 * {@code Forwarded-Client-Cert} (SPIFFE ID). Pins the security invariants:
 *
 * <ul>
 *   <li>no FPC header &rarr; 401 on decision AND admin endpoints (fail-closed);</li>
 *   <li>a non-admin workload &rarr; 200 on decisions, 403 on admin;</li>
 *   <li>an admin workload &rarr; 201 on admin, and the audit actor is the
 *       <b>verified SPIFFE principal</b> — a spoofed {@code X-Actor} header is
 *       ignored;</li>
 *   <li>actuator health stays open for the platform probes.</li>
 * </ul>
 *
 * <p>Skipped (not failed) when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rbac.pdp.auth.enabled=true",
        "rbac.pdp.auth.mode=mesh",
        "rbac.pdp.auth.admin-authority=rbac-admin",
        "rbac.pdp.auth.admin-principals=spiffe://cluster.local/ns/prod/sa/rbac-console"
})
class AccessServiceAuthMeshTest {

    private static final String ORDER_SVC = "spiffe://cluster.local/ns/prod/sa/orders-svc";
    private static final String ADMIN_SVC = "spiffe://cluster.local/ns/prod/sa/rbac-console";

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

    private HttpHeaders fpc(String spiffe) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("Forwarded-Client-Cert", "San=\"" + spiffe + "\";CertChain=base64junk");
        return h;
    }

    @Test
    void noFpcHeaderIsUnauthorized_everywhere() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);

        assertThat(rest.exchange("/v1/grants", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", "u-1", "profiles", Set.of("ops")), h), Void.class)
                .getStatusCode())
                .as("decision endpoint without a mesh identity").isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(rest.exchange("/v1/admin/profiles", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "p-1"), h), Void.class)
                .getStatusCode())
                .as("admin endpoint without a mesh identity").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void actuatorHealthStaysOpen_forThePlatformProbes() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .as("the readiness/liveness probe must not require auth").isEqualTo(HttpStatus.OK);
    }

    @Test
    void aNonAdminWorkloadCanDecide_butNotAuthor() {
        // decision is allowed (identity verified by the mesh)
        HttpHeaders h = fpc(ORDER_SVC);
        assertThat(rest.exchange("/v1/grants", HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", "u-1", "profiles", Set.of("ops")), h), Map.class)
                .getStatusCode())
                .as("decision for a verified workload").isEqualTo(HttpStatus.OK);

        // but admin is forbidden
        HttpHeaders a = fpc(ORDER_SVC);
        assertThat(rest.exchange("/v1/admin/profiles", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "p-1"), a), Void.class)
                .getStatusCode())
                .as("admin for a non-admin workload").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAdminWorkloadCanAuthor_andTheActorIsTheVerifiedPrincipal_notTheHeader() {
        HttpHeaders h = fpc(ADMIN_SVC);
        h.add("X-Actor", "spoofed-actor"); // must be IGNORED (S1 invariant)

        assertThat(rest.exchange("/v1/admin/profiles", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "auth-mesh-profile"), h), Map.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        String actor = jdbc.queryForObject(
                "select actor from audit_change where entity_ref = 'auth-mesh-profile' "
                        + "order by id desc limit 1",
                String.class);
        assertThat(actor)
                .as("the audit actor is the verified SPIFFE principal, not the X-Actor header")
                .isEqualTo(ADMIN_SVC);
    }
}
