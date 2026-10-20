package com.example.rbac.client.it;

import com.example.rbac.client.cache.L1Cache;
import com.example.rbac.model.Bus;
import com.example.rbac.model.dto.GrantSet;
import com.example.rbac.model.dto.InvalidationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
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
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3 (REVIEW #7): subscriber durability. The per-instance invalidation queue is
 * <b>auto-delete</b> — it vanishes with the connection when the broker restarts. This
 * test deletes the queue out from under a live consumer (the same end state as a
 * broker restart) and proves the subscriber:
 *
 * <ul>
 *   <li>does not die (missing queue is non-fatal; the container's unbounded
 *       consumer-restart loop keeps running);</li>
 *   <li>re-declares the queue + binding and re-subscribes; and</li>
 *   <li>still delivers invalidation events afterwards (L1 is cleared).</li>
 * </ul>
 *
 * <p>The outage is also visible on the metrics: {@code rbac.client.bus.lost} is
 * incremented and the {@code rbac.client.bus.up} gauge returns to 1.
 *
 * <p>A full broker-restart IT (container restart) is deliberately avoided:
 * Testcontainers re-maps ports on restart, which would leave the
 * {@code @ServiceConnection}-wired context pointing at a dead port. Deleting the
 * queue exercises exactly the code path a restart triggers (queue gone + consumer
 * must re-declare and re-subscribe) without that flake.
 *
 * <p>Skipped (not failed) when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = InvalidationSubscriberReconnectTest.TestApp.class,
        properties = "rbac.client.invalidation-queue=it.starter.reconnect")
class InvalidationSubscriberReconnectTest {

    static final String QUEUE = "it.starter.reconnect";

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.12-alpine");

    /** A bare application: auto-configuration only (the starter is a library, not an app). */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Autowired L1Cache l1;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper json;
    @Autowired MeterRegistry registry;

    @Test
    void survivesQueueLoss_redeclaresAndDeliversInvalidation() throws Exception {
        // 0. The subscriber has a live consumer on its per-instance queue.
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(this::consumerOnQueue);

        // Warm L1 the way DefaultAccessClient does after a PDP fetch.
        GrantSet grantSet = new GrantSet(List.of("orders:create"), null, null, 1L);
        l1.put(Set.of("ops"), grantSet);
        assertThat(l1.get(Set.of("ops"))).isNotNull();

        // 1. Simulate a broker restart: the auto-delete queue is gone with the connection.
        //    (The consumer's channel errors; without the fix the subscriber would die here.)
        assertThat(rabbitAdmin.deleteQueue(QUEUE)).as("the queue existed before the loss").isTrue();

        // 2. The subscriber must re-declare the queue + binding and re-subscribe.
        //    (Monitor re-declares within ~2 s; the container's restart loop is every ~5 s.)
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(this::consumerOnQueue);

        // 3. An event published after the loss must still be delivered (proves re-subscription).
        Message event = MessageBuilder
                .withBody(json.writeValueAsBytes(InvalidationEvent.all(3L)))
                .setContentType("application/json")
                .build();
        rabbitTemplate.send(Bus.EXCHANGE, Bus.ROUTING_KEY, event);
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(l1.get(Set.of("ops"))).as("L1 evicted after the bus loss").isNull());

        // 4. The outage was visible on the metrics, and the bus is reported up again.
        //    (The gauge is refreshed by the subscriber's monitor tick, so await it.)
        Counter lost = registry.find("rbac.client.bus.lost").counters().stream().findFirst().orElse(null);
        assertThat(lost).as("a bus-outage transition was recorded").isNotNull();
        assertThat(lost.count()).isGreaterThanOrEqualTo(1.0);
        Gauge up = registry.find("rbac.client.bus.up").gauges().stream().findFirst().orElse(null);
        assertThat(up).as("the bus-up gauge exists").isNotNull();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(up.value()).as("the bus is up again after recovery").isEqualTo(1.0));
    }

    private boolean consumerOnQueue() {
        try {
            return rabbitAdmin.getQueueInfo(QUEUE).getConsumerCount() >= 1;
        } catch (Exception e) {
            return false; // queue not (re)declared yet
        }
    }
}
