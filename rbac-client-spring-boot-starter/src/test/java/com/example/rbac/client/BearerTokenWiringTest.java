package com.example.rbac.client;

import com.example.rbac.client.autoconfigure.RbacClientAutoConfiguration;
import com.example.rbac.client.security.BearerTokenProvider;
import com.example.rbac.model.UserContext;
import com.example.rbac.model.dto.GrantSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1 (ADR-002): the client presents a bearer token to a {@code mode=jwt} PDP.
 *
 * <ul>
 *   <li>{@code rbac.client.pdp-token} set &rarr; a default {@link BearerTokenProvider};</li>
 *   <li>neither the property nor a host bean &rarr; <b>no</b> provider (no header —
 *       correct for {@code mode=mesh} / disabled-auth PDPs);</li>
 *   <li>the header actually reaches the wire, exactly once, and an already-prefixed
 *       token is not doubled.</li>
 * </ul>
 *
 * <p>The end-to-end cases boot the real auto-configuration against a local
 * single-port fake PDP (JDK {@link HttpServer}) that records the
 * {@code Authorization} header.
 */
class BearerTokenWiringTest {

    private static final GrantSet GRANT_SET = new GrantSet(List.of("orders:create"), null, null, Map.of(), 7L);

    private HttpServer server;
    private final List<String> authHeaders = Collections.synchronizedList(new ArrayList<>());

    private void startFakePdp() {
        ObjectMapper json = new ObjectMapper();
        byte[] body;
        try {
            body = json.writeValueAsBytes(GRANT_SET);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/grants", exchange -> {
                authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to start the fake PDP", e);
        }
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        RbacClientAutoConfiguration.class));
    }

    // --- bean wiring ---

    @Test
    void aConfiguredPdpToken_yieldsTheDefaultProvider() {
        runner().withPropertyValues(
                        "rbac.client.baseUrl=http://pdp.local",
                        "rbac.client.pdp-token=tok-123")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(BearerTokenProvider.class);
                    assertThat(ctx.getBean(BearerTokenProvider.class).bearerToken())
                            .isEqualTo("tok-123");
                });
    }

    @Test
    void withoutATokenPropertyOrBean_thereIsNoProvider() {
        runner().withPropertyValues("rbac.client.baseUrl=http://pdp.local")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BearerTokenProvider.class));
    }

    // --- end-to-end: the header reaches the wire ---

    @Test
    void theConfiguredTokenIsSentToThePdp_asAnAuthorizationHeader() {
        startFakePdp();
        runner().withPropertyValues(
                        "rbac.client.baseUrl=http://127.0.0.1:" + server.getAddress().getPort(),
                        "rbac.client.pdp-token=tok-123")
                .run(ctx -> {
                    AccessClient client = ctx.getBean(AccessClient.class);
                    GrantSet gs = client.grants(new UserContext("u-1", Set.of("ops")));
                    assertThat(gs.permissions()).containsExactly("orders:create");
                });
        assertThat(authHeaders)
                .as("the PDP sees exactly one Authorization header")
                .containsExactly("Bearer tok-123");
    }

    @Test
    void anAlreadyPrefixedToken_isNotDoubled() {
        startFakePdp();
        runner().withPropertyValues(
                        "rbac.client.baseUrl=http://127.0.0.1:" + server.getAddress().getPort(),
                        "rbac.client.pdp-token=Bearer pre-tok")
                .run(ctx -> ctx.getBean(AccessClient.class)
                        .grants(new UserContext("u-2", Set.of("ops"))));
        assertThat(authHeaders).containsExactly("Bearer pre-tok");
    }

    @Test
    void withoutAToken_noAuthorizationHeaderIsSent() {
        startFakePdp();
        runner().withPropertyValues(
                        "rbac.client.baseUrl=http://127.0.0.1:" + server.getAddress().getPort())
                .run(ctx -> ctx.getBean(AccessClient.class)
                        .grants(new UserContext("u-3", Set.of("ops"))));
        assertThat(authHeaders).as("no provider configured → no Authorization header")
                .containsExactly((String) null);
    }
}
