package com.example.rbac.service.invalidation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Transactional outbox for invalidation (C6, REVIEW #9).
 *
 * <p>Stores the INTENT ("a bump + publish is owed"), not the version — the version
 * bump is a Redis INCR side-effect the relay performs ({@link InvalidationRelay}).
 * Atomicity comes from writing the row in the SAME Postgres transaction as the grant
 * change ({@code AdminService} calls {@link #append()} inside its {@code @Transactional}
 * method), so "change committed &rArr; invalidation owed" survives a Redis/RabbitMQ
 * outage at commit time and a crash right after commit.
 *
 * <p>At-least-once: a row may be published more than once. That is harmless — the
 * version only ever moves forward and a client takes max(localVersion, v) + clears L1,
 * so a duplicate is a no-op. Exactly-once is not required.
 */
@Component
public class OutboxService {

    private final JdbcTemplate jdbc;

    public OutboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Append a new invalidation intent within the CURRENT transaction (the caller must
     * be {@code @Transactional}). Returns the new row id — the unit of work the fast
     * path and the relay both act on.
     */
    public long append() {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement(
                        "insert into outbox_invalidation default values", new String[]{"id"}),
                keys);
        return keys.getKey().longValue();
    }

    /**
     * Atomically claim a row for processing. Returns {@code true} only if THIS caller
     * won: the row is unpublished and either unclaimed or claimed more than 30s ago
     * (a crashed claimant's lease expires, so its row is re-claimable). Two workers
     * racing the same row: exactly one returns {@code true} — the {@code UPDATE ... WHERE}
     * takes the row lock, and a blocked claimant re-evaluates the predicate against the
     * freshly committed {@code claimed_at} after the winner releases it.
     */
    public boolean tryClaim(long id) {
        int updated = jdbc.update(
                "update outbox_invalidation set claimed_at = now() "
                        + "where id = ? and published_at is null "
                        + "and (claimed_at is null or claimed_at < now() - interval '30 seconds')",
                id);
        return updated == 1;
    }

    /** Mark a row published, recording the policy version it was stamped under. */
    public void markPublished(long id, long version) {
        jdbc.update(
                "update outbox_invalidation set published_at = now(), version = ? where id = ?",
                version, id);
    }

    /**
     * The real alarm (REVIEW #9): committed-but-unpublished intents. Should sit at 0 in
     * steady state; a non-zero value means the fast path missed and the relay is
     * behind (or the broker is still down).
     */
    public long pending() {
        Long c = jdbc.queryForObject(
                "select count(*) from outbox_invalidation where published_at is null", Long.class);
        return (c == null) ? 0L : c;
    }

    /** Oldest unpublished intents, in order — the relay's next work batch. */
    public List<Long> pendingIds(int limit) {
        return jdbc.queryForList(
                "select id from outbox_invalidation where published_at is null "
                        + "order by id asc limit ?", Long.class, limit);
    }
}
