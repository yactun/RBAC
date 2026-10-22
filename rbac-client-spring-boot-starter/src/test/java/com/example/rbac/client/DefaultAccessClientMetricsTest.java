package com.example.rbac.client;

import com.example.rbac.client.autoconfigure.MicrometerRbacClientMetrics;
import com.example.rbac.client.cache.L1Cache;
import com.example.rbac.client.cache.L2Cache;
import com.example.rbac.model.UserContext;
import com.example.rbac.model.dto.CheckResult;
import com.example.rbac.model.dto.GrantSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the client's metrics wiring ({@link RbacClientMetrics} sink):
 * L1/L2 hit-miss, decision latency by serving source, PDP-fetch success/failure,
 * fail-closed behavior — no infrastructure needed (canned PDP via a stub
 * {@link ClientHttpRequestFactory}, fake L2 via a proxied
 * {@link ValueOperations}).
 */
class DefaultAccessClientMetricsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RbacClientMetrics metrics = new MicrometerRbacClientMetrics(registry);

    private static final GrantSet GRANT_SET = new GrantSet(List.of("orders:create"), null, null, Map.of(), 5L);

    private L1Cache freshL1() {
        return new L1Cache(Duration.ofMinutes(5), 100L);
    }

    private DefaultAccessClient client(L1Cache l1, L2Cache l2, ClientHttpRequestFactory factory) {
        RestClient rest = RestClient.builder().baseUrl("http://pdp.local").requestFactory(factory).build();
        return new DefaultAccessClient(l1, l2, rest, json, metrics);
    }

    private UserContext user(String id) {
        return new UserContext(id, Set.of("ops"));
    }

    private double count(String name, String... tags) {
        // counters() (plural): a lenient search — absent meters mean 0, not an exception
        return registry.find(name).tags(tags).counters().stream().mapToDouble(Counter::count).sum();
    }

    private Timer timer(String name, String... tags) {
        return registry.find(name).tags(tags).timers().stream().findFirst().orElse(null);
    }

    // --- L1 hit: served locally, no fetch, hit metric ---

    @Test
    void l1Hit_servesLocally_andRecordsHitMetrics() {
        L1Cache l1 = freshL1();
        l1.put(Set.of("ops"), GRANT_SET);
        DefaultAccessClient client = client(l1, null, throwingFactory());

        CheckResult result = client.check(user("u-1"), "orders:create");
        assertThat(result.allowed()).isTrue();

        assertThat(count("rbac.client.cache", "tier", "l1", "result", "hit")).isEqualTo(1.0);
        assertThat(count("rbac.client.cache", "tier", "l1", "result", "miss")).isZero();
        assertThat(count("rbac.client.pdp.fetch", "success", "true")).isZero();
        Timer t = timer("rbac.client.decision", "operation", "check", "source", "l1");
        assertThat(t).as("decision timer tagged with the serving source").isNotNull();
        assertThat(t.count()).isEqualTo(1L);
    }

    // --- L1 miss (no L2): fetch from PDP, record fetch, warm L1 ---

    @Test
    void l1Miss_fetchesFromPdp_recordsFetch_andWarmsL1() {
        L1Cache l1 = freshL1();
        DefaultAccessClient client = client(l1, null, cannedFactory());
        UserContext u = user("u-2");

        GrantSet gs = client.grants(u);
        assertThat(gs.permissions()).containsExactly("orders:create");

        assertThat(count("rbac.client.cache", "tier", "l1", "result", "miss")).isEqualTo(1.0);
        assertThat(count("rbac.client.pdp.fetch", "success", "true")).isEqualTo(1.0);
        assertThat(timer("rbac.client.decision", "operation", "grants", "source", "pdp")).isNotNull();

        // second call: served from the now-warm L1, no second fetch
        client.grants(u);
        assertThat(count("rbac.client.cache", "tier", "l1", "result", "hit")).isEqualTo(1.0);
        assertThat(count("rbac.client.pdp.fetch", "success", "true")).isEqualTo(1.0);
    }

    // --- L2 hit: shared tier serves, warms L1, no PDP fetch ---

    @Test
    void l2Hit_servesFromSharedTier_andWarmsL1() {
        L1Cache l1 = freshL1();
        L2Cache l2 = fakeL2();
        DefaultAccessClient client = client(l1, l2, throwingFactory());
        UserContext u = user("u-3");

        GrantSet gs = client.grants(u);
        assertThat(gs.permissions()).containsExactly("orders:create");

        assertThat(count("rbac.client.cache", "tier", "l1", "result", "miss")).isEqualTo(1.0);
        assertThat(count("rbac.client.cache", "tier", "l2", "result", "hit")).isEqualTo(1.0);
        assertThat(count("rbac.client.pdp.fetch", "success", "true")).isZero();
        assertThat(timer("rbac.client.decision", "operation", "grants", "source", "l2")).isNotNull();

        client.grants(u); // now from L1
        assertThat(count("rbac.client.cache", "tier", "l1", "result", "hit")).isEqualTo(1.0);
    }

    // --- L2 (Redis) down: bypass the tier, fall through to the PDP (REVIEW #5) ---

    @Test
    void l2Failure_bypassesTier_fallsThroughToPdp_andRecordsError() {
        L1Cache l1 = freshL1();
        L2Cache l2 = throwingL2(); // simulates a Redis outage
        DefaultAccessClient client = client(l1, l2, cannedFactory());
        UserContext u = user("u-l2down");

        // grants: served from the PDP (Redis bypassed), not an exception
        GrantSet gs = client.grants(u);
        assertThat(gs.permissions()).containsExactly("orders:create");

        // check: fail-closed contract still holds, and it resolves via the PDP
        CheckResult result = client.check(u, "orders:create");
        assertThat(result.allowed()).isTrue();

        assertThat(count("rbac.client.cache", "tier", "l2", "result", "error")).isEqualTo(1.0);
        assertThat(count("rbac.client.pdp.fetch", "success", "true")).isEqualTo(1.0);
        assertThat(timer("rbac.client.decision", "operation", "grants", "source", "pdp")).isNotNull();
    }

    // --- PDP failure: fail-closed + failure is visible in metrics ---

    @Test
    void pdpFailure_failsClosed_andRecordsFailedFetch() {
        L1Cache l1 = freshL1();
        DefaultAccessClient client = client(l1, null, throwingFactory());
        UserContext u = user("u-4");

        // check: fail-closed deny (never throws)
        CheckResult denied = client.check(u, "orders:create");
        assertThat(denied.allowed()).isFalse();

        // scope: fail-closed throw (→ 500 upstream)
        assertThatThrownBy(() -> client.scope(u, "orders"))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("rbac.client.pdp.fetch", "success", "false")).isEqualTo(2.0);
        assertThat(count("rbac.client.cache", "tier", "l1", "result", "miss")).isEqualTo(2.0);
        // failed decisions are visible under source=error (SLO-relevant)
        assertThat(timer("rbac.client.decision", "operation", "check", "source", "error")).isNotNull();
        assertThat(timer("rbac.client.decision", "operation", "scope", "source", "error")).isNotNull();
    }

    // --- no metrics sink (L1-only host without Micrometer): client still works ---

    @Test
    void noMetricsSink_clientStillWorks() {
        L1Cache l1 = freshL1();
        l1.put(Set.of("ops"), GRANT_SET);
        RestClient rest = RestClient.builder().baseUrl("http://pdp.local").build();
        DefaultAccessClient client = new DefaultAccessClient(l1, null, rest, json); // 4-arg: no-op sink

        CheckResult result = client.check(user("u-5"), "orders:create");
        assertThat(result.allowed()).isTrue();
        assertThat(registry.getMeters()).as("nothing recorded without a sink").isEmpty();
    }

    // --- stubs ---

    /** PDP stub: serves the canned GrantSet as JSON for POST /v1/grants. */
    private ClientHttpRequestFactory cannedFactory() {
        byte[] body;
        try {
            body = json.writeValueAsBytes(GRANT_SET);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return (uri, method) -> new ClientHttpRequest() {
            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }

            @Override
            public HttpMethod getMethod() {
                return method;
            }

            @Override
            public URI getURI() {
                return uri;
            }

            @Override
            public Map<String, Object> getAttributes() {
                return new java.util.HashMap<>(); // mutable: RestClient mutates it
            }

            @Override
            public OutputStream getBody() {
                return new ByteArrayOutputStream();
            }

            @Override
            public ClientHttpResponse execute() {
                return new ClientHttpResponse() {
                    @Override
                    public HttpHeaders getHeaders() {
                        HttpHeaders h = new HttpHeaders();
                        h.set(HttpHeaders.CONTENT_TYPE, "application/json");
                        return h;
                    }

                    @Override
                    public HttpStatusCode getStatusCode() {
                        return HttpStatusCode.valueOf(200);
                    }

                    @Override
                    public String getStatusText() {
                        return "OK";
                    }

                    @Override
                    public InputStream getBody() {
                        return new ByteArrayInputStream(body);
                    }

                    @Override
                    public void close() {
                        // no-op
                    }
                };
            }
        };
    }

    /** PDP stub: always fails (connection refused, simulating an outage). */
    private ClientHttpRequestFactory throwingFactory() {
        return (uri, method) -> {
            throw new IOException("connection refused (simulated PDP outage)");
        };
    }

    /** L2 stub: the shared key always holds the canned GrantSet JSON. */
    private L2Cache fakeL2() {
        String raw;
        try {
            raw = json.writeValueAsString(GRANT_SET);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = (ValueOperations<String, String>) Proxy.newProxyInstance(
                ValueOperations.class.getClassLoader(),
                new Class<?>[]{ ValueOperations.class },
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) {
                        return raw;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        StringRedisTemplate template = new StringRedisTemplate() {
            @Override
            public ValueOperations<String, String> opsForValue() {
                return ops;
            }
        };
        return new L2Cache(template, json);
    }

    /** L2 stub: every read throws (simulates a Redis outage). */
    private L2Cache throwingL2() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = (ValueOperations<String, String>) Proxy.newProxyInstance(
                ValueOperations.class.getClassLoader(),
                new Class<?>[]{ ValueOperations.class },
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) {
                        throw new IllegalStateException("simulated Redis outage (connection refused)");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        StringRedisTemplate template = new StringRedisTemplate() {
            @Override
            public ValueOperations<String, String> opsForValue() {
                return ops;
            }
        };
        return new L2Cache(template, json);
    }
}
