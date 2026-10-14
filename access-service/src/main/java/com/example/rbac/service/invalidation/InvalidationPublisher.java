package com.example.rbac.service.invalidation;

import com.example.rbac.model.Bus;
import com.example.rbac.model.dto.InvalidationEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes invalidation events to the bus so every client replica drops stale
 * L1 entries (ADR D6). Fire-and-forget is acceptable: a missed event degrades a
 * client to its TTL bound, never to a wrong decision.
 */
@Component
public class InvalidationPublisher {

    private final RabbitTemplate rabbit;

    public InvalidationPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public void publish(InvalidationEvent event) {
        rabbit.convertAndSend(Bus.EXCHANGE, Bus.ROUTING_KEY, event);
    }
}
