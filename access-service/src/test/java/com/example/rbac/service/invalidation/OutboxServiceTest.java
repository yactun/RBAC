package com.example.rbac.service.invalidation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-PostgreSQL test of the outbox SQL semantics (C6). The atomic claim is the
 * thing that makes at-least-once safe: two workers racing the same row must yield
 * exactly one winner. That is a property of the {@code UPDATE ... WHERE} row-lock, so
 * it is only meaningful against a real database — and it is tested in ISOLATION (no
 * full Spring app, hence no relay poller to race with).
 *
 * <p>Skipped (not failed) when Docker is unavailable (AGENTS.md gotcha #17).
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxServiceTest {

    @Container
    // Distinct database name: guarantees a separate container from the full-app IT
    // (so this isolated `create table` can't collide with Flyway's V3 on a reused host).
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("outbox")
            .withUsername("access")
            .withPassword("access");

    static JdbcTemplate jdbc;
    static OutboxService outbox;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        outbox = new OutboxService(jdbc);
        // The exact V3 shape (a fresh, isolated table — no other schema needed here).
        jdbc.execute("create table outbox_invalidation ("
                + "id bigint generated always as identity primary key,"
                + "created_at timestamptz not null default now(),"
                + "published_at timestamptz,"
                + "version bigint,"
                + "claimed_at timestamptz)");
    }

    // The test methods share one table (and `pending()` is a global count), so each
    // starts from a clean slate for a deterministic assertion.
    @BeforeEach
    void cleanTable() {
        jdbc.update("truncate outbox_invalidation");
    }

    private long newRow() {
        return outbox.append();
    }

    @Test
    void append_returnsDistinctIds_andPendingTracksUnpublished() {
        long a = newRow();
        long b = newRow();
        assertThat(b).as("identity ids are distinct and increasing").isGreaterThan(a);
        assertThat(outbox.pending()).isEqualTo(2);

        outbox.markPublished(a, 7L);
        assertThat(outbox.pending()).isEqualTo(1);
        Long stamped = jdbc.queryForObject(
                "select version from outbox_invalidation where id = ?", Long.class, a);
        assertThat(stamped).as("markPublished records the stamped version").isEqualTo(7L);
    }

    @Test
    void tryClaim_secondCallOnSameRowLoses() {
        long id = newRow();
        assertThat(outbox.tryClaim(id)).as("the first claim wins").isTrue();
        assertThat(outbox.tryClaim(id)).as("the second claim loses (already claimed)").isFalse();
    }

    @Test
    void tryClaim_concurrentCallers_exactlyOneWins() throws Exception {
        long id = newRow();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> outbox.tryClaim(id)));
            }
            pool.shutdown();
            long wins = results.stream()
                    .mapToLong(f -> {
                        try {
                            return f.get() ? 1L : 0L;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .sum();
            assertThat(wins).as("the atomic claim admits exactly one winner").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tryClaim_reclaimsAfterTheLeaseExpires() {
        long id = newRow();
        assertThat(outbox.tryClaim(id)).as("the first claim wins").isTrue();
        assertThat(outbox.tryClaim(id)).as("a fresh claim is not re-claimable").isFalse();

        // Simulate a crashed claimant: age the lease past the 30s expiry.
        jdbc.update("update outbox_invalidation set claimed_at = now() - interval '31 seconds' where id = ?", id);
        assertThat(outbox.tryClaim(id)).as("a stale claim is re-claimable").isTrue();
    }

    @Test
    void pendingIds_oldestFirst_andExcludesPublished() {
        long a = newRow();
        long b = newRow();
        long c = newRow();
        outbox.markPublished(b, 1L);

        List<Long> ids = outbox.pendingIds(10);
        assertThat(ids).as("published rows are excluded, oldest first").containsExactly(a, c);
    }
}
