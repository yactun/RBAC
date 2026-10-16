package com.example.rbac.service.invalidation;

/**
 * Published (synchronously, inside the admin transaction) to signal that grants
 * changed and clients must be invalidated. Carries the id of the outbox row that
 * records the "bump + publish is owed" intent ({@link OutboxService#append()}),
 * written in the SAME transaction as the grant change.
 *
 * <p>The actual version bump + bus publish happens AFTER the transaction commits
 * ({@link InvalidationOnCommit} → {@link InvalidationRelay#process(long)}), so a client
 * can never observe a new policy version before the change is durable. If that publish
 * fails (Redis/RabbitMQ down), the outbox row stays pending and the relay re-drives it —
 * the invalidation is owed, never lost.
 */
public record GrantChangeEvent(long outboxId) {
}
