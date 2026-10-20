package com.example.rbac.client.it;

import com.example.rbac.client.AccessClient;
import com.example.rbac.client.RbacClientMetrics;
import com.example.rbac.client.cache.L1Cache;
import com.example.rbac.client.cache.L2Cache;
import com.example.rbac.client.invalidation.InvalidationSubscriber;
import com.example.rbac.model.Bus;
import com.example.rbac.model.CacheKeys;
import com.example.rbac.model.dto.GrantSet;
import com.example.rbac.model.dto.InvalidationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client-side half of the invalidation round-trip (ADR D6/D10), with real
 * infrastructure (Testcontainers):
 *
 * <ul>
 *   <li>the starter's auto-configuration wires L1, L2, the {@link AccessClient},
 *       and the RabbitMQ {@link InvalidationSubscriber} (own per-instance
 *       auto-delete queue on the shared exchange);</li>
 *   <li>an {@link InvalidationEvent} published exactly the way the PDP does
 *       (JSON on {@link Bus#EXCHANGE}/{@link Bus#ROUTING_KEY}) clears the
 *       client's L1;</li>
 *   <li>the L2 read uses the shared {@link CacheKeys} scheme — the contract the
 *       PDP writes to (regression guard on the shared key contract).</li>
 * </ul>
 *
 * <p>Skipped (not failed) when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = InvalidationSubscriberIntegrationTest.TestApp.class,
        properties = "rbac.client.invalidation-queue=it.starter.test")
class InvalidationSubscriberIntegrationTest {

    static final String QUEUE = "it.starter.test";

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.12-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /** A bare application: auto-configuration only (the starter is a library, not an app). */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Autowired L1Cache l1;
    @Autowired L2Cache l2;
    @Autowired AccessClient accessClient;
    @Autowired InvalidationSubscriber subscriber;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired ObjectMapper json;
    @Autowired RbacClientMetrics metrics;
    @Autowired MeterRegistry registry;

    @Test
    void autoConfigurationWiresClientAndSubscriber() {
        assertThat(accessClient).as("the AccessClient bean").isNotNull();
        assertThat(l2).as("the L2 cache bean (Redis on the classpath)").isNotNull();
        assertThat(subscriber).as("the invalidation subscriber bean").isNotNull();
        assertThat(subscriber.isRunning()).as("SmartLifecycle started the listener container").isTrue();
    }

    @Test
    void autoConfiguration_wiresMicrometerMetrics_whenRegistryPresent() {
        // actuator is on the test classpath -> a MeterRegistry bean exists, so the
        // auto-config must provide the Micrometer adapter (not the no-op sink)
        assertThat(metrics).as("auto-configured sink is the Micrometer adapter")
                .isNotSameAs(RbacClientMetrics.NONE);
        assertThat(registry).isNotNull();
    }

    @Test
    void invalidationEventOnBus_clearsClientL1() throws Exception {
        // Warm L1 the way DefaultAccessClient does after a PDP fetch
        GrantSet grantSet = new GrantSet(List.of("orders:create"), Map.of(), Map.of(), 1L);
        l1.put(Set.of("ops"), grantSet);
        assertThat(l1.get(Set.of("ops"))).isSameAs(grantSet);

        // Wait until the subscriber has a live consumer, so the publish below
        // cannot be dropped by the auto-delete queue.
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(this::consumerOnQueue);

        // Publish exactly what the PDP publishes: a JSON InvalidationEvent on the shared exchange
        Message event = MessageBuilder
                .withBody(json.writeValueAsBytes(InvalidationEvent.all(2L)))
                .setContentType("application/json")
                .build();
        rabbitTemplate.send(Bus.EXCHANGE, Bus.ROUTING_KEY, event);

        // The subscriber must raise the local version and clear L1
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(l1.get(Set.of("ops"))).as("L1 evicted by the bus event").isNull());

        // ...and the event must be counted (SLO/ops evidence)
        Counter events = registry.get("rbac.client.invalidation.events").counter();
        assertThat(events).as("invalidation events are counted").isNotNull();
        assertThat(events.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void l2ReadUsesTheSharedCacheKeyScheme() throws Exception {
        GrantSet grantSet = new GrantSet(List.of("orders:create"), Map.of(), Map.of(), 7L);
        // Write as the PDP does (shared key scheme + JSON body)
        redisTemplate.opsForValue().set(CacheKeys.grantsKey(Set.of("ops")),
                json.writeValueAsString(grantSet));
        // The starter must read it back from that exact key
        assertThat(l2.get(Set.of("ops"))).as("L2 read at the shared CacheKeys key").isEqualTo(grantSet);
    }

    private boolean consumerOnQueue() {
        try {
            return rabbitAdmin.getQueueInfo(QUEUE).getConsumerCount() >= 1;
        } catch (Exception e) {
            return false; // queue not declared yet
        }
    }
}
