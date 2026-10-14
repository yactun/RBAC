package com.example.rbac.model.dto;

/**
 * Published on the invalidation bus (RabbitMQ) whenever grants change.
 * Clients use it to raise their local policy version and drop stale L1 entries.
 * {@code userId}/{@code profile} may be null for a global "everything changed" signal.
 */
public record InvalidationEvent(String userId, String profile, long version, long timestamp) {

    public static InvalidationEvent all(long version) {
        return new InvalidationEvent(null, null, version, System.currentTimeMillis());
    }

    public static InvalidationEvent forUser(String userId, long version) {
        return new InvalidationEvent(userId, null, version, System.currentTimeMillis());
    }
}
