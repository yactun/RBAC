package com.example.rbac.service.it;

import com.example.rbac.model.Bus;
import com.example.rbac.model.CacheKeys;
import com.example.rbac.model.dto.CheckResult;
import com.example.rbac.model.dto.GrantSet;
import com.example.rbac.model.dto.InvalidationEvent;
import com.example.rbac.model.dto.Scope;
import com.example.rbac.model.dto.UiElements;
import com.example.rbac.service.cache.GrantCache;
import com.example.rbac.service.cache.VersionService;
import com.example.rbac.service.invalidation.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Live-infrastructure integration test (Testcontainers) for the PDP: boots the
 * full application against real PostgreSQL (Flyway V1+V2), Redis (L2 cache +
 * policy version) and RabbitMQ (invalidation bus), then exercises over HTTP:
 *
 * <ul>
 *   <li>startup: migrations applied, audit partitioned, Redis + bus reachable;</li>
 *   <li>admin change &rarr; version bump + {@link InvalidationEvent} on the bus
 *       (AFTER commit) + synchronous change audit;</li>
 *   <li>decision path: grants/check/scope/ui + L2 write under the shared
 *       {@link CacheKeys} scheme (ADR D9/D10);</li>
 *   <li>revocation: bump + invalidation + the stale cached GrantSet is
 *       recomputed, so the permission is gone.</li>
 * </ul>
 *
 * <p>Skipped (not failed) when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessServiceIntegrationTest {

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
    @Autowired VersionService versions;
    @Autowired GrantCache grantCache;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry registry;
    @Autowired OutboxService outbox;

    // --- startup against live infrastructure ---

    @Test
    void contextBoots_flywayApplied_auditPartitioned_redisAndBusReachable() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(applied).as("Flyway applied V1 + V2 + V3 on the real PostgreSQL").isGreaterThanOrEqualTo(3);

        Integer auditTables = jdbc.queryForObject(
                "select count(*) from pg_class where relname like 'audit_decision%'", Integer.class);
        assertThat(auditTables).as("audit parent + default + first month partition").isGreaterThanOrEqualTo(3);

        assertThat(versions.current()).as("policy version readable on a fresh Redis").isGreaterThanOrEqualTo(0L);

        // passive declare throws if the exchange is missing
        org.assertj.core.api.Assertions.assertThatCode(
                () -> rabbitTemplate.execute(ch -> ch.exchangeDeclarePassive(Bus.EXCHANGE)))
                .as("invalidation exchange declared on the bus").doesNotThrowAnyException();
    }

    // --- admin change -> version bump -> invalidation on the bus -> change audit ---

    @Test
    void adminChange_bumpsVersion_publishesInvalidation_andWritesChangeAudit() throws Exception {
        long before = versions.current();
        String profile = "it-profile-" + System.nanoTime();
        Queue queue = standinClientQueue();

        ResponseEntity<Map> created = rest.postForEntity("/v1/admin/profiles",
                new HttpEntity<>(Map.of("name", profile, "description", "integration test"), actor()), Map.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        assertThat(versions.current()).as("version bumped exactly once, after commit").isEqualTo(before + 1);

        Message message = rabbitTemplate.receive(queue.getName(), 5_000);
        assertThat(message).as("invalidation event reached the stand-in client queue").isNotNull();
        InvalidationEvent event = json.readValue(message.getBody(), InvalidationEvent.class);
        assertThat(event.version()).isEqualTo(before + 1);
        assertThat(event.userId()).as("global invalidation carries no userId").isNull();

        Integer changeAudits = jdbc.queryForObject(
                "select count(*) from audit_change where entity_ref = ? and action = 'create'",
                Integer.class, profile);
        assertThat(changeAudits).as("synchronous change audit row").isEqualTo(1);

        // C6 outbox: the committed change wrote an invalidation intent in-txn, and the
        // fast path (after commit) published it — so nothing is left pending.
        Integer outboxPending = jdbc.queryForObject(
                "select count(*) from outbox_invalidation where published_at is null", Integer.class);
        assertThat(outboxPending).as("no outbox row left pending after the fast path").isEqualTo(0);
        Integer outboxPublished = jdbc.queryForObject(
                "select count(*) from outbox_invalidation where published_at is not null", Integer.class);
        assertThat(outboxPublished).as("the committed change's invalidation intent was published")
                .isGreaterThanOrEqualTo(1);
    }

    // --- C6 outbox: the durable fallback (relay) recovers a lost fast path ---

    @Test
    void relayRecoversPendingRow_andPublishesOnBus() throws Exception {
        long before = versions.current();
        Queue queue = standinClientQueue();

        // Simulate a committed change whose fast path was lost (e.g. Redis/RabbitMQ was
        // down at commit): the outbox row is committed but unpublished.
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement(
                "insert into outbox_invalidation default values", new String[]{"id"}), keys);
        long id = keys.getKey().longValue();
        assertThat(outbox.pending()).as("a pending invalidation intent exists").isGreaterThanOrEqualTo(1);

        // The relay (1s poll) must recover it: claim, bump, publish, mark published.
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> outbox.pending() == 0L);

        Integer published = jdbc.queryForObject(
                "select count(*) from outbox_invalidation where id = ? and published_at is not null",
                Integer.class, id);
        assertThat(published).as("the pending row was marked published by the relay").isEqualTo(1);
        Long stamped = jdbc.queryForObject(
                "select version from outbox_invalidation where id = ?", Long.class, id);
        assertThat(stamped).as("stamped with a version newer than before").isGreaterThanOrEqualTo(before + 1);

        Message message = rabbitTemplate.receive(queue.getName(), 5_000);
        assertThat(message).as("the relay published an invalidation to the bus").isNotNull();
        assertThat(json.readValue(message.getBody(), InvalidationEvent.class).version())
                .as("the bus event carries a newer version").isGreaterThanOrEqualTo(before + 1);
    }

    @Test
    void adminInvalidate_forcesBumpAndPublish_bypassingOutbox() throws Exception {
        long before = versions.current();
        Queue queue = standinClientQueue();

        ResponseEntity<Map> resp =
                rest.postForEntity("/v1/admin/invalidate", new HttpEntity<>(actor()), Map.class);
        assertThat(resp.getStatusCode().value()).as("escape hatch succeeds").isEqualTo(200);
        // The JSON round-trip hands back an Integer, so compare numerically (not by type).
        assertThat(((Number) resp.getBody().get("version")).longValue())
                .as("returns the new version").isEqualTo(before + 1);

        Message message = rabbitTemplate.receive(queue.getName(), 5_000);
        assertThat(message).as("force-invalidate published an event to the bus").isNotNull();
        assertThat(json.readValue(message.getBody(), InvalidationEvent.class).version())
                .isEqualTo(before + 1);
    }

    // --- full decision flow + revocation round-trip ---

    @Test
    void decisionFlow_grantsCheckScopeUi_l2Write_andRevocation() throws Exception {
        String profile = "it-ops-" + System.nanoTime();
        Set<String> profiles = Set.of(profile);

        // --- authoring via the admin API (each call bumps the version + invalidates) ---
        assertThat(post("/v1/admin/profiles",
                Map.of("name", profile, "description", "integration test"))).isEqualTo(201);
        assertThat(post("/v1/admin/profiles/" + profile + "/permissions", Map.of("key", "orders:create")))
                .isEqualTo(200);
        assertThat(post("/v1/admin/resource-types", Map.of(
                "name", "orders",
                "ownerService", "orders-svc",
                "dimensionSchema", Map.of("orgIds", Map.of("type", "list", "item", "string")))))
                .isEqualTo(201);
        assertThat(put("/v1/admin/profiles/" + profile + "/scopes/orders", Map.of("orgIds", List.of(1, 2))))
                .isEqualTo(200);
        assertThat(post("/v1/admin/templates", Map.of(
                "appId", "customers-web",
                "name", "standard",
                "elements", List.of("customers-web.tab.invoices", "customers-web.tab.orders"))))
                .isEqualTo(201);
        assertThat(post("/v1/admin/profiles/" + profile + "/templates",
                Map.of("appId", "customers-web", "name", "standard"))).isEqualTo(200);

        // --- decision path: the single cached unit (ADR D9) ---
        ResponseEntity<GrantSet> grantsResp = rest.postForEntity("/v1/grants",
                new HttpEntity<>(Map.of("userId", "u-1", "profiles", profiles), new HttpHeaders()), GrantSet.class);
        assertThat(grantsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        GrantSet gs = grantsResp.getBody();
        assertThat(gs).isNotNull();

        // T1 (ADR-002): the template is a LAYOUT CATALOG — attaching it does NOT grant
        // its elements' permissions. Only the explicitly granted key is in the grant set.
        assertThat(gs.permissions()).containsExactly("orders:create");

        Scope scope = gs.scopes().get("orders");
        assertThat(scope).as("data scope for the 'orders' resource type").isNotNull();
        assertThat(scope.values("orgIds")).containsExactly(1, 2);

        // T1: the UI bundle LISTS the template's elements, but both are hidden because
        // their gating permissions were never granted (visible:false is reachable).
        UiElements ui = gs.ui().get("customers-web");
        assertThat(ui).as("UI bundle for the template's app").isNotNull();
        assertThat(ui.elements()).extracting(UiElements.Element::key, UiElements.Element::visible)
                .contains(tuple("tab.invoices", false), tuple("tab.orders", false));

        // T1 regression: granting the gating permission flips the element visible,
        // and REVOKING it flips it back (previously the template re-granted it, so
        // the revoke was a silent no-op — the #4 bug this fixes).
        assertThat(post("/v1/admin/profiles/" + profile + "/permissions",
                Map.of("key", "customers-web.tab.invoices"))).isEqualTo(200);
        GrantSet gsGranted = rest.postForEntity("/v1/grants",
                new HttpEntity<>(Map.of("userId", "u-1", "profiles", profiles), new HttpHeaders()), GrantSet.class).getBody();
        assertThat(gsGranted.permissions()).contains("customers-web.tab.invoices");
        assertThat(gsGranted.ui().get("customers-web").elements())
                .extracting(UiElements.Element::key, UiElements.Element::visible)
                .contains(tuple("tab.invoices", true), tuple("tab.orders", false));

        assertThat(rest.exchange(
                URI.create("/v1/admin/profiles/" + profile + "/permissions/customers-web.tab.invoices"),
                HttpMethod.DELETE, new HttpEntity<>(actor()), Map.class)
                .getStatusCode().value()).isEqualTo(200);
        GrantSet gsRevoked = rest.postForEntity("/v1/grants",
                new HttpEntity<>(Map.of("userId", "u-1", "profiles", profiles), new HttpHeaders()), GrantSet.class).getBody();
        assertThat(gsRevoked.permissions()).doesNotContain("customers-web.tab.invoices");
        assertThat(gsRevoked.ui().get("customers-web").elements())
                .extracting(UiElements.Element::key, UiElements.Element::visible)
                .as("revoking the gating permission hides the element again (revoke is effective)")
                .contains(tuple("tab.invoices", false));

        // check: granted (allowed + grantedBy) and denied (no grant)
        CheckResult allowed = check("u-1", profiles, "orders:create");
        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.grantedBy()).contains(profile);
        assertThat(check("u-1", profiles, "orders:refund").allowed()).isFalse();

        // L2: the PDP wrote the GrantSet under the shared key scheme — the same
        // key the starter's L2Cache reads (regression guard on the shared contract).
        assertThat(grantCache.get("u-1", profiles)).as("L2 hit via GrantCache").isNotNull();
        assertThat(redisTemplate.opsForValue().get(CacheKeys.grantsKey("u-1", profiles)))
                .as("L2 value present at the shared CacheKeys key in Redis").isNotNull();

        // Decision audit is async -> poll for both check rows
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                jdbc.queryForObject("select count(*) from audit_decision where user_id = 'u-1'", Integer.class) >= 2);

        // --- revocation round-trip ---
        long before = versions.current();
        Queue queue = standinClientQueue();
        assertThat(rest.exchange(
                URI.create("/v1/admin/profiles/" + profile + "/permissions/orders:create"),
                HttpMethod.DELETE, new HttpEntity<>(actor()), Map.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(versions.current()).isEqualTo(before + 1);

        Message message = rabbitTemplate.receive(queue.getName(), 5_000);
        assertThat(message).as("invalidation event on the bus after revocation").isNotNull();
        assertThat(json.readValue(message.getBody(), InvalidationEvent.class).version()).isEqualTo(before + 1);

        assertThat(jdbc.queryForObject(
                "select count(*) from audit_change where entity_ref = ? and action = 'revoke_permission'"
                        + " and detail::text like ?",
                Integer.class, profile, "%orders:create%"))
                .as("the orders:create revocation is audited (distinct from the T1 UI-perm revoke above)")
                .isEqualTo(1);

        // The PDP recomputes (the cached GrantSet is stale by version) -> the
        // permission is no longer served.
        assertThat(check("u-1", profiles, "orders:create").allowed())
                .as("revoked permission is no longer served").isFalse();
    }

    // --- metrics (SLO evidence: decision latency + cache hit-rate, exported via OTel) ---

    @Test
    void metrics_decisionLatencyAndCacheHitRate_recorded_andOtlpBackendOnClasspath() {
        String profile = "it-metrics-" + System.nanoTime();
        assertThat(post("/v1/admin/profiles",
                Map.of("name", profile, "description", "metrics test"))).isEqualTo(201);
        assertThat(post("/v1/admin/profiles/" + profile + "/permissions", Map.of("key", "orders:create")))
                .isEqualTo(200);
        Set<String> profiles = Set.of(profile);

        // first check: L2 miss (compute + write); second: L2 hit (same version, no bump between)
        assertThat(check("u-metrics", profiles, "orders:create").allowed()).isTrue();
        assertThat(check("u-metrics", profiles, "orders:create").allowed()).isTrue();

        Timer checkTimer = registry.get("rbac.pdp.decision").tags("operation", "check").timer();
        assertThat(checkTimer).as("decision latency timer is recorded").isNotNull();
        assertThat(checkTimer.count()).isGreaterThanOrEqualTo(2);

        Counter l2Hit = registry.get("rbac.pdp.cache").tags("tier", "l2", "result", "hit").counter();
        Counter l2Miss = registry.get("rbac.pdp.cache").tags("tier", "l2", "result", "miss").counter();
        assertThat(l2Miss).as("L2 miss is recorded").isNotNull();
        assertThat(l2Miss.count()).isGreaterThanOrEqualTo(1);
        assertThat(l2Hit).as("L2 hit is recorded").isNotNull();
        assertThat(l2Hit.count()).isGreaterThanOrEqualTo(1);

        // C6 outbox metrics: the publish counter is recorded and the pending gauge sits
        // at 0 (the healthy fast path leaves nothing unpublished).
        Counter published = registry.get("rbac.pdp.outbox.published").counter();
        assertThat(published).as("outbox publish counter is recorded").isNotNull();
        assertThat(published.count()).isGreaterThanOrEqualTo(1);
        Double pending = registry.get("rbac.pdp.outbox.pending").gauge().value();
        assertThat(pending).as("no outbox row left pending (fast path published it)").isEqualTo(0.0);

        // Backend: OpenTelemetry (CNCF standard) — the OTLP registry is a runtime
        // dependency. Boot wires it whenever metrics export is enabled; Boot's test
        // policy disables export in @SpringBootTest (hence a SimpleMeterRegistry
        // here) so we assert the dependency rather than the test-environment registry.
        assertThatCode(() -> Class.forName("io.micrometer.registry.otlp.OtlpMeterRegistry"))
                .as("the OTel (OTLP) registry is on the runtime classpath")
                .doesNotThrowAnyException();
    }

    // --- helpers ---

    private HttpHeaders actor() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Actor", "it-qa");
        return h;
    }

    private int post(String url, Object body) {
        return rest.postForEntity(url, new HttpEntity<>(body, actor()), Map.class).getStatusCode().value();
    }

    private int put(String url, Object body) {
        return rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, actor()), Map.class).getStatusCode().value();
    }

    private CheckResult check(String userId, Set<String> profiles, String key) {
        Map<String, Object> body = Map.of("user", Map.of("userId", userId, "profiles", profiles), "key", key);
        return rest.postForEntity("/v1/check", new HttpEntity<>(body, new HttpHeaders()), CheckResult.class).getBody();
    }

    /**
     * A stand-in client queue bound to the shared invalidation exchange (the same
     * broadcast pattern a real client uses), so the test can observe the events
     * the PDP publishes.
     */
    private Queue standinClientQueue() {
        String name = "it.standin." + System.nanoTime();
        Queue queue = QueueBuilder.durable(name).build();
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue)
                .to(new TopicExchange(Bus.EXCHANGE, true, false))
                .with(Bus.ROUTING_KEY));
        return queue;
    }
}
