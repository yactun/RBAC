package com.example.rbac.model;

/**
 * Shared invalidation-bus (RabbitMQ) names. The PDP declares the durable topic
 * exchange and publishes to it; each client declares its own per-instance,
 * non-durable, auto-delete queue bound to the same exchange + routing key
 * (broadcast pattern — one shared queue would be consumed competitively).
 * Exchange and routing key must match on both sides, so they live in the
 * shared model module.
 */
public final class Bus {

    public static final String EXCHANGE = "rbac.invalidation";
    public static final String ROUTING_KEY = "rbac.invalidated";
    /** A sensible default client queue name; each client may override. */
    public static final String DEFAULT_QUEUE = "rbac.client.invalidated";

    private Bus() {
    }
}
