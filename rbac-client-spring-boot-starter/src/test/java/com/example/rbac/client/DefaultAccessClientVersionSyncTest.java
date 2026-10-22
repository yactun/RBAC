package com.example.rbac.client;

import com.example.rbac.client.autoconfigure.MicrometerRbacClientMetrics;
import com.example.rbac.client.cache.L1Cache;
import com.example.rbac.client.cache.L2Cache;
import com.example.rbac.client.invalidation.InvalidationHandler;
import com.example.rbac.model.UserContext;
import com.example.rbac.model.dto.GrantSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * C2 (REVIEW #6): a fresh replica must seed its local policy version from the PDP
 * ({@code GET /v1/version}) so it does not accept a stale L2 entry written before its
 * first invalidation event. No infrastructure needed (canned PDP, fake L2).
 */
class DefaultAccessClientVersionSyncTest {

    private final ObjectMapper json = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RbacClientMetrics metrics = new MicrometerRbacClientMetrics(registry);

    /** The stale L2 entry (written before this replica saw any invalidation event). */
    private static final GrantSet STALE = new GrantSet(List.of("orders:create"), null, null, Map.of(), 7L);
    /** The current PDP answer — newer than the stale L2 entry. */
    private static final GrantSet FRESH = new GrantSet(List.of("orders:create"), null, null, Map.of(), 11L);
    /** What the PDP's /v1/version probe reports. */
    private static final long PDP_VERSION = 10L;

    private UserContext user(String id) {
        return new UserContext(id, Set.of("ops"));
    }

    private DefaultAccessClient client(L1Cache l1, L2Cache l2, ClientHttpRequestFactory factory) {
        RestClient rest = RestClient.builder().baseUrl("http://pdp.local").requestFactory(factory).build();
        return new DefaultAccessClient(l1, l2, rest, json, metrics);
    }

    private double count(String name, String... tags) {
        return registry.find(name).tags(tags).counters().stream().mapToDouble(Counter::count).sum();
    }

    @Test
    void control_withoutSync_aStaleL2EntryIsServed() {
        // Documents the hole C2 closes: a fresh replica (localVersion=0) trusts L2 blindly.
        L1Cache l1 = new L1Cache(Duration.ofMinutes(5), 100L);
        DefaultAccessClient client = client(l1, staleL2(), versionAndGrantsFactory());

        GrantSet gs = client.grants(user("u-c2"));

        assertThat(gs.version()).isEqualTo(STALE.version());
        assertThat(count("rbac.client.cache", "tier", "l2", "result", "hit")).isEqualTo(1.0);
    }

    @Test
    void syncVersion_seedsLocalVersion_andStaleL2IsRejected() {
        L1Cache l1 = new L1Cache(Duration.ofMinutes(5), 100L);
        DefaultAccessClient client = client(l1, staleL2(), versionAndGrantsFactory());

        client.syncVersion();

        GrantSet gs = client.grants(user("u-c2"));

        assertThat(gs.version())
                .as("served the FRESH PDP grant set, not the stale L2 entry")
                .isEqualTo(FRESH.version());
        assertThat(count("rbac.client.cache", "tier", "l2", "result", "miss")).isEqualTo(1.0);
        assertThat(count("rbac.client.pdp.fetch", "success", "true")).isEqualTo(1.0);
    }

    @Test
    void onBusConnected_reSyncsVersion_andStaleL2IsRejected() {
        L1Cache l1 = new L1Cache(Duration.ofMinutes(5), 100L);
        DefaultAccessClient client = client(l1, staleL2(), versionAndGrantsFactory());

        // The subscriber calls this when the bus is (re)established.
        ((InvalidationHandler) client).onBusConnected();

        GrantSet gs = client.grants(user("u-c2"));

        assertThat(gs.version()).isEqualTo(FRESH.version());
    }

    @Test
    void syncVersion_isFailSoft_whenPdpDown() {
        L1Cache l1 = new L1Cache(Duration.ofMinutes(5), 100L);
        DefaultAccessClient client = client(l1, null, throwingFactory());

        assertThatCode(() -> client.syncVersion()).doesNotThrowAnyException();
    }

    // --- stubs ---

    /** PDP stub: GET /v1/version → {"version": 10}; POST /v1/grants → the fresh GrantSet. */
    private ClientHttpRequestFactory versionAndGrantsFactory() {
        byte[] version;
        byte[] grants;
        try {
            version = json.writeValueAsBytes(Map.of("version", PDP_VERSION));
            grants = json.writeValueAsBytes(FRESH);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return (uri, method) -> new Canned(uri, method, version, grants);
    }

    /** PDP stub: always fails (connection refused, simulating an outage). */
    private ClientHttpRequestFactory throwingFactory() {
        return (uri, method) -> {
            throw new java.io.IOException("connection refused (simulated PDP outage)");
        };
    }

    /** L2 stub: the shared key holds the STALE GrantSet JSON. */
    private L2Cache staleL2() {
        String raw;
        try {
            raw = json.writeValueAsString(STALE);
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

    /** Canned HTTP request: GET serves the version body, everything else the grants body. */
    private static final class Canned implements ClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final byte[] versionBody;
        private final byte[] grantsBody;

        Canned(URI uri, HttpMethod method, byte[] versionBody, byte[] grantsBody) {
            this.uri = uri;
            this.method = method;
            this.versionBody = versionBody;
            this.grantsBody = grantsBody;
        }

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
            byte[] body = (method == HttpMethod.GET) ? versionBody : grantsBody;
            return new CannedResponse(body);
        }
    }

    private static final class CannedResponse implements ClientHttpResponse {
        private final byte[] body;

        CannedResponse(byte[] body) {
            this.body = body;
        }

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
    }
}
