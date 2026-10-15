package com.example.rbac.service.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Audit (ADR D8). Decision writes are {@code @Async} so they never sit on the
 * decision hot path; change writes are synchronous because the mutation *is* the
 * event and must be durable with the transaction. Tables are monthly-partitioned
 * (see db/migration/V2__audit.sql).
 */
@Component
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Async
    public void decision(String userId, Set<String> profiles, String op, String target,
                         boolean allowed, long version, String requestId, String callingService) {
        try {
            // ts is left to the column default (now()): the DB clock is the
            // source of truth for the partition key, and pgjdbc refuses to bind
            // java.time.Instant without an explicit SQL type (see AGENTS.md #21).
            jdbc.update(
                    "insert into audit_decision (user_id, profile_set, op, target, allowed, version, request_id, calling_service) "
                            + "values (?, ?::text[], ?, ?, ?, ?, ?, ?)",
                    userId, profiles.toArray(new String[0]), op, target, allowed, version, requestId, callingService);
        } catch (Exception e) {
            // Never let audit failure break the decision path; log loudly.
            log.error("Failed to write decision audit for user={} op={}", userId, op, e);
        }
    }

    public void change(String actor, String entity, String entityRef, String action, String detailJson) {
        // ts is left to the column default (now()) — see decision() for why we
        // don't bind an Instant parameter.
        jdbc.update(
                "insert into audit_change (actor, entity, entity_ref, action, detail) values (?, ?, ?, ?, ?::jsonb)",
                actor, entity, entityRef, action, detailJson);
    }
}
