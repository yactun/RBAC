package com.example.rbac.client;

import com.example.rbac.client.cache.L1Cache;
import com.example.rbac.model.UserContext;
import com.example.rbac.model.dto.GrantSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C5b (REVIEW #10): stampede protection. N concurrent L1 misses for the same key must
 * produce exactly ONE PDP fetch — Caffeine's per-key locking
 * ({@link L1Cache#getOrLoad}) coalesces the rest.
 */
class L1CacheCoalescingTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void concurrentL1Misses_coalesceIntoASinglePdpFetch() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        byte[] body;
        try {
            body = json.writeValueAsBytes(new GrantSet(List.of("orders:create"), null, null, 3L));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        // Every executed request is a real PDP fetch — count them.
        ClientHttpRequestFactory factory = (uri, method) -> new CountingRequest(uri, method, body, fetches);

        L1Cache l1 = new L1Cache(Duration.ofMinutes(5), 100L);
        RestClient rest = RestClient.builder().baseUrl("http://pdp.local").requestFactory(factory).build();
        // 4-arg constructor: no-op metrics sink (L1-only host shape)
        DefaultAccessClient client = new DefaultAccessClient(l1, null, rest, json);

        UserContext u = new UserContext("u-stampede", Set.of("ops"));
        int n = 16;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<GrantSet>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(); // all threads hit the cold cache together
                    return client.grants(u);
                }));
            }
            for (Future<GrantSet> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS).permissions())
                        .as("every caller gets the grant set")
                        .containsExactly("orders:create");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(fetches.get())
                .as("exactly one PDP fetch despite 16 concurrent cold misses")
                .isEqualTo(1);
        // The coalesced result was cached: follow-up reads are L1 hits, no more fetches.
        client.grants(u);
        assertThat(fetches.get()).isEqualTo(1);
    }

    /** Canned PDP response; counts every executed request as a real fetch. */
    private static final class CountingRequest implements ClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final byte[] body;
        private final AtomicInteger fetches;

        CountingRequest(URI uri, HttpMethod method, byte[] body, AtomicInteger fetches) {
            this.uri = uri;
            this.method = method;
            this.body = body;
            this.fetches = fetches;
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
            fetches.incrementAndGet();
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
