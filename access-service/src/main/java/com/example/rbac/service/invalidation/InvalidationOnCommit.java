package com.example.rbac.service.invalidation;

import com.example.rbac.model.dto.InvalidationEvent;
import com.example.rbac.service.cache.VersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Performs the version bump + invalidation publish only AFTER the admin
 * transaction commits.
 *
 * <p>The old behavior published <i>inside</i> the transaction. A client that
 * refetched in the small gap between publish and commit would read the not-yet
 * committed (old) grants, stamped with the already-bumped version, and cache them
 * as fresh for the whole TTL — exactly the silent revocation loss D10 is meant to
 * prevent. Deferring to {@code AFTER_COMMIT} fixes the ordering to
 * commit → bump → publish.
 *
 * <p>If Redis or RabbitMQ is unavailable at that moment the invalidation is
 * LOST (the change is already committed): it is logged at ERROR and clients
 * degrade to TTL-bounded staleness — the documented safety net, never a wrong
 * decision. The change itself is never reported as failed. The durable
 * long-term fix is a transactional outbox (the relay performs the bump +
 * publish, so both ride on the committed intent).
 */
@Component
public class InvalidationOnCommit {

    private static final Logger log = LoggerFactory.getLogger(InvalidationOnCommit.class);

    private final VersionService version;
    private final InvalidationPublisher publisher;

    public InvalidationOnCommit(VersionService version, InvalidationPublisher publisher) {
        this.version = version;
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommit(GrantChangeEvent event) {
        try {
            long v = version.bump();
            publisher.publish(InvalidationEvent.all(v));
        } catch (RuntimeException e) {
            // The change is COMMITTED — a 500 here would be a lie. Log loudly so
            // ops can re-trigger; clients degrade to TTL-bounded staleness (D10).
            log.error("Grant change committed but invalidation was NOT published; "
                    + "clients stay stale until TTL expiry. Re-run the admin change or "
                    + "bump the policy version manually. cause={}", e.toString());
        }
    }
}
