package com.example.rbac.client.invalidation;

import com.example.rbac.client.RbacClientMetrics;
import com.example.rbac.model.Bus;
import com.example.rbac.model.dto.InvalidationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Client side of the invalidation bus (ADR D6). Declares its own per-instance
 * auto-delete queue bound to the shared exchange (names from {@link Bus}), and consumes
 * {@link InvalidationEvent}s to clear stale local L1 entries and raise the local
 * policy version. Self-contained: it drives its own listener container, so the
 * host app does not need {@code @EnableRabbit}. A {@link SmartLifecycle} so the
 * container starts on context-ready and stops on shutdown.
 *
 * <p>Durability (REVIEW #7): the per-instance queue is <b>auto-delete</b>, so it
 * disappears with the connection when the broker restarts. The listener
 * container's consumer-restart loop (default: every 5 s, unbounded) only keeps
 * working if (a) a missing queue is <i>not</i> fatal ({@code missingQueuesFatal=false}),
 * and (b) something re-declares the queue + binding — the container's built-in
 * re-declaration requires an {@link org.springframework.context.ApplicationContext},
 * which this standalone container has none, so a lightweight monitor thread
 * re-declares it. The monitor also reports the bus state (lost/restored) via
 * the metrics sink and asks the handler to re-sync on restore.
 */
public class InvalidationSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InvalidationSubscriber.class);

    /** Monitor period: how often the bus is checked for liveness and re-declaration. */
    static final long MONITOR_PERIOD_MS = 2_000L;

    private final SimpleMessageListenerContainer container;
    private final RabbitAdmin admin;
    private final TopicExchange exchange;
    private final Queue queue;
    private final Binding binding;
    private final InvalidationHandler handler;
    private final RbacClientMetrics metrics;

    private volatile boolean running = false;
    private volatile boolean busKnown = false;
    private volatile boolean busActive = false;
    private ScheduledExecutorService monitor;

    public InvalidationSubscriber(ConnectionFactory connectionFactory,
                                  String queueName,
                                  ObjectMapper json,
                                  InvalidationHandler handler) {
        this(connectionFactory, queueName, json, handler, RbacClientMetrics.NONE);
    }

    public InvalidationSubscriber(ConnectionFactory connectionFactory,
                                  String queueName,
                                  ObjectMapper json,
                                  InvalidationHandler handler,
                                  RbacClientMetrics metrics) {
        // Declare the shared exchange + our own per-instance auto-delete queue + binding.
        // Non-durable + auto-delete is the correct broadcast pattern: the exchange fans
        // each event out to every bound (per-instance) queue so every replica receives
        // it, and the queue is cleaned up when its consumer disconnects. Losing a
        // message only degrades a replica to its TTL bound (the version check is the
        // real guarantee), so durability is not needed here.
        this.admin = new RabbitAdmin(connectionFactory);
        this.exchange = ExchangeBuilder.topicExchange(Bus.EXCHANGE).durable(true).build();
        this.queue = QueueBuilder.nonDurable(queueName).autoDelete().build();
        this.binding = BindingBuilder.bind(this.queue).to(this.exchange).with(Bus.ROUTING_KEY);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(binding);

        this.handler = handler;
        this.metrics = (metrics == null) ? RbacClientMetrics.NONE : metrics;

        this.container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queueName);
        container.setMessageListener(message -> {
            try {
                InvalidationEvent event = json.readValue(message.getBody(), InvalidationEvent.class);
                handler.onEvent(event);
            } catch (Exception e) {
                log.warn("Invalidation handling failed: {}", e.toString());
            }
        });
        // A missing queue must NOT be fatal: our queue is auto-delete and vanishes with
        // a broker restart; the container's unbounded consumer-restart loop (default
        // 5 s backoff) then keeps trying, and the monitor below re-declares the queue
        // so the next attempt succeeds.
        container.setMissingQueuesFatal(false);
        container.setAmqpAdmin(admin);
    }

    @Override
    public void start() {
        container.start();
        running = true;
        monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rbac-invalid-bus-monitor");
            t.setDaemon(true);
            return t;
        });
        monitor.scheduleAtFixedRate(this::checkBus, MONITOR_PERIOD_MS, MONITOR_PERIOD_MS, TimeUnit.MILLISECONDS);
        log.info("Invalidation subscriber started (exchange={})", Bus.EXCHANGE);
    }

    /**
     * Bus liveness tick (every {@link #MONITOR_PERIOD_MS}):
     * <ul>
     *   <li>re-declares the auto-delete queue + binding while the container has no live
     *       consumer (the broker restarted; our queue is gone) so the container's
     *       consumer-restart loop can re-subscribe;</li>
     *   <li>reports lost/restored transitions on the metrics sink;</li>
     *   <li>asks the handler to re-sync (local policy version) when the bus is restored.</li>
     * </ul>
     */
    void checkBus() {
        try {
            boolean active = running && container.isRunning() && container.getActiveConsumerCount() > 0;
            if (!busKnown) {
                busKnown = true;
                busActive = active;
                metrics.busState(active); // initial report (no transition semantics)
                if (!active) {
                    redeclare();
                }
                return;
            }
            if (active == busActive) {
                if (!active) {
                    redeclare(); // keep retrying while the bus is down
                }
                return;
            }
            // A transition (bus lost, or restored) — report it exactly once.
            busActive = active;
            metrics.busState(active);
            if (active) {
                log.info("Invalidation bus (re)established — asking the client to re-sync");
                try {
                    handler.onBusConnected();
                } catch (Exception e) {
                    log.warn("onBusConnected handling failed: {}", e.toString());
                }
            } else {
                log.warn("Invalidation bus lost — degrading to TTL-bounded staleness until reconnect");
                redeclare();
            }
        } catch (Exception e) {
            log.debug("Bus monitor tick failed: {}", e.toString());
        }
    }

    /** Re-declare exchange + queue + binding (idempotent). Swallows failures: while the
     *  broker is down there is nothing to declare against; the next tick retries. */
    private void redeclare() {
        try {
            admin.declareExchange(exchange);
            admin.declareQueue(queue);
            admin.declareBinding(binding);
        } catch (Exception e) {
            log.debug("Queue re-declaration skipped (bus still down): {}", e.toString());
        }
    }

    @Override
    public void stop() {
        running = false;
        if (monitor != null) {
            monitor.shutdownNow();
            monitor = null;
        }
        try {
            container.stop();
        } catch (Exception ignore) {
            // best-effort shutdown
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
