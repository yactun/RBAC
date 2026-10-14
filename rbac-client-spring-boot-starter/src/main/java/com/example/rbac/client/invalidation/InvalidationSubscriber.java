package com.example.rbac.client.invalidation;

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

/**
 * Client side of the invalidation bus (ADR D6). Declares its own per-instance
 * auto-delete queue bound to the shared exchange (names from {@link Bus}), and consumes
 * {@link InvalidationEvent}s to clear stale local L1 entries and raise the local
 * policy version. Self-contained: it drives its own listener container, so the
 * host app does not need {@code @EnableRabbit}. A {@link SmartLifecycle} so the
 * container starts on context-ready and stops on shutdown.
 */
public class InvalidationSubscriber implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InvalidationSubscriber.class);

    private final SimpleMessageListenerContainer container;
    private volatile boolean running = false;

    public InvalidationSubscriber(ConnectionFactory connectionFactory,
                                  String queueName,
                                  ObjectMapper json,
                                  InvalidationHandler handler) {
        // Declare the shared exchange + our own per-instance auto-delete queue + binding.
        // Non-durable + auto-delete is the correct broadcast pattern: the exchange fans
        // each event out to every bound (per-instance) queue so every replica receives
        // it, and the queue is cleaned up when its consumer disconnects. Losing a
        // message only degrades a replica to its TTL bound (the version check is the
        // real guarantee), so durability is not needed here.
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        TopicExchange exchange = ExchangeBuilder.topicExchange(Bus.EXCHANGE).durable(true).build();
        Queue queue = QueueBuilder.nonDurable(queueName).autoDelete().build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(Bus.ROUTING_KEY);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(binding);

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
    }

    @Override
    public void start() {
        container.start();
        running = true;
        log.info("Invalidation subscriber started (exchange={})", Bus.EXCHANGE);
    }

    @Override
    public void stop() {
        running = false;
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
