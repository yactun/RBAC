-- V3__outbox.sql — transactional outbox for invalidation (C6, REVIEW #9)
--
-- The outbox stores the INTENT ("a bump + publish is owed"), not the version.
-- The version bump is a Redis INCR side-effect the relay performs. Atomicity
-- comes from writing this row in the SAME Postgres transaction as the grant
-- change (see AdminService), so "change committed => invalidation owed"
-- survives a Redis/RabbitMQ outage at commit time AND a crash right after commit.
--
-- At-least-once: a row may be published more than once (crash-retry, or two
-- replicas racing for the same row). That is harmless — the version only ever
-- moves forward, and a client takes max(localVersion, v) + clears L1, so a
-- duplicate event is a no-op. Exactly-once is deliberately NOT required.
--
-- The `id` is the unit of work (v1 has no per-row payload — invalidation is
-- global). `claimed_at` is a lease so a crashed claimant's row becomes
-- re-claimable after 30s (see OutboxService.tryClaim).
create table outbox_invalidation (
  id           bigint generated always as identity primary key,
  created_at   timestamptz not null default now(),
  published_at timestamptz,   -- NULL = committed but not yet published (the alarm)
  version      bigint,        -- the policy version stamped at publish time
  claimed_at   timestamptz    -- last worker that claimed the row (claim lease)
);

-- The relay's work queue: oldest unpublished rows first. A partial index keeps
-- the pending set small and makes the poll + the pending gauge cheap.
create index ix_outbox_pending on outbox_invalidation (id) where published_at is null;
