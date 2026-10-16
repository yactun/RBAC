package com.example.rbac.service.invalidation;

import com.example.rbac.model.dto.InvalidationEvent;
import com.example.rbac.service.cache.VersionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Performs the version bump + invalidation publish for a committed grant change.
 *
 * <p>Two entry points share {@link #process(long)}:
 * <ul>
 *   <li><b>Fast path</b> — {@link InvalidationOnCommit} calls it right after the admin
 *       transaction commits, so the healthy case is near-instant (as before);</li>
 *   <li><b>Relay</b> — the {@code @Scheduled} poll below re-drives any row the fast
 *       path missed because Redis/RabbitMQ was down at that moment (the durable
 *       fallback that removes the old "invalidation lost" foot-gun).</li>
 * </ul>
 *
 * <p>At-least-once safe: a row may be processed more than once (crash-retry, or two
 * replicas racing). That is harmless — the version only ever moves forward and a
 * duplicate publish is a no-op for clients (they take max(localVersion, v) + clear L1).
 * Ordering is preserved: the version is bumped only AFTER the change committed.
 *
 * <p>Metrics (REVIEW #9): {@code rbac.pdp.outbox.pending} gauge (committed-but-
 * unpublished — the real alarm, replaces the old "lost" counter) plus
 * {@code rbac.pdp.outbox.published} / {@code .failed} counters.
 */
@Component
public class InvalidationRelay {

    private static final Logger log = LoggerFactory.getLogger(InvalidationRelay.class);

    private final OutboxService outbox;
    private final VersionService version;
    private final InvalidationPublisher publisher;
    private final MeterRegistry metrics;

    public InvalidationRelay(OutboxService outbox, VersionService version,
                             InvalidationPublisher publisher, MeterRegistry metrics) {
        this.outbox = outbox;
        this.version = version;
        this.publisher = publisher;
        this.metrics = metrics;
        // Registered once; evaluated at scrape time (a count of unpublished rows).
        // Gauge creation is idempotent by name, so a second registration is a no-op.
        metrics.gauge("rbac.pdp.outbox.pending", outbox, OutboxService::pending);
    }

    /**
     * Process one committed outbox row: claim it, bump the version, publish a global
     * invalidation, mark it published. At-least-once safe (see class javadoc).
     *
     * <p>Swallows + records failures so a single bad row can't wedge the relay: the row
     * stays unpublished and is retried on the next poll (or by the ops escape hatch).
     * Never throws — the fast path runs on the request thread right after commit, and
     * the change is already COMMITTED, so a 500 here would be a lie.
     */
    public void process(long outboxId) {
        if (!outbox.tryClaim(outboxId)) {
            return;   // another worker owns it (or already published)
        }
        try {
            long v = version.bump();
            publisher.publish(InvalidationEvent.all(v));
            outbox.markPublished(outboxId, v);
            metrics.counter("rbac.pdp.outbox.published").increment();
        } catch (RuntimeException e) {
            metrics.counter("rbac.pdp.outbox.failed").increment();
            // Leave published_at NULL so the next relay poll retries. Log loudly so ops
            // sees the pending gauge climb and can use POST /v1/admin/invalidate.
            log.error("Outbox row {} could not be published; it stays pending and will be "
                    + "retried by the relay. cause={}", outboxId, e.toString());
        }
    }

    /**
     * Durable fallback: every second, re-drive any committed-but-unpublished row. This
     * is what makes invalidation survive a Redis/RabbitMQ outage at the moment the admin
     * transaction committed (the fast path is best-effort). Deliberately a simple poll —
     * LISTEN/NOTIFY and exactly-once are out of scope for v1 (1s latency on the degraded
     * path is acceptable; the healthy path is instant).
     */
    @Scheduled(fixedDelay = 1_000L)
    public void poll() {
        try {
            for (Long id : outbox.pendingIds(100)) {
                process(id);
            }
        } catch (RuntimeException e) {
            // Transient (e.g. the DB is briefly unavailable). The rows stay pending and
            // the next poll retries; don't let one bad poll wedge the scheduler or spam
            // the log (a real outage shows up as the pending gauge climbing, not here).
            log.warn("Outbox relay poll failed; will retry next cycle. cause={}", e.toString());
        }
    }

    /**
     * Ops escape hatch: force an immediate global bump + publish right now, bypassing the
     * outbox (it must not depend on the relay, which may be the very thing that is stuck).
     * Returns the new policy version.
     */
    public long forceInvalidate() {
        long v = version.bump();
        publisher.publish(InvalidationEvent.all(v));
        metrics.counter("rbac.pdp.outbox.published").increment();
        return v;
    }
}
