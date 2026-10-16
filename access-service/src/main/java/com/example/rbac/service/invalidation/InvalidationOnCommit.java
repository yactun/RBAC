package com.example.rbac.service.invalidation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fires the fast path: right AFTER the admin transaction commits, process the outbox
 * row (bump the version + publish the invalidation), so the healthy case stays
 * near-instant.
 *
 * <p>Deferring to {@code AFTER_COMMIT} keeps the ordering commit → bump → publish, so a
 * client refetching in the small gap can never read not-yet-committed grants stamped
 * with an already-bumped version (the silent revocation loss D10 is meant to prevent).
 *
 * <p>This is BEST-EFFORT: if Redis or RabbitMQ is down at that moment,
 * {@link InvalidationRelay#process(long)} leaves the outbox row unpublished and the
 * relay re-drives it. The change is COMMITTED and the invalidation is OWED — never lost,
 * never reported as a failure.
 */
@Component
public class InvalidationOnCommit {

    private final InvalidationRelay relay;

    public InvalidationOnCommit(InvalidationRelay relay) {
        this.relay = relay;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommit(GrantChangeEvent event) {
        relay.process(event.outboxId());
    }
}
