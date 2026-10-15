-- V2__audit.sql — partitioned audit tables (design §5.3, ADR D8)
-- Partitioned by month on `ts`. Ops adds next-month partitions ahead of time
-- (see the default partition below); archive/drop per retention policy.
--
-- SYNTAX NOTE (learned the hard way): PostgreSQL does NOT accept a column
-- list (including `LIKE parent`) in `CREATE TABLE ... PARTITION OF parent`.
-- A partition inherits ALL columns, identity properties and constraints from
-- the parent automatically, so the only valid form is:
--   create table <name> partition of <parent> [for values ... | default];
-- (verified against PostgreSQL 16.15; `... (like parent including identity)
--  partition of ...` is a syntax error at "of").

create table audit_decision (
  id              bigint generated always as identity,
  ts              timestamptz not null default now(),
  user_id         text not null,
  profile_set     text[] not null,
  op              text not null,
  target          text,
  allowed         boolean,
  version         bigint,
  request_id      text,
  calling_service text,
  primary key (id, ts)
) partition by range (ts);

create table audit_decision_default partition of audit_decision default;

-- First month partition as a placeholder; roll forward via ops runbook.
create table audit_decision_2026_10 partition of audit_decision for values from ('2026-10-01') to ('2026-11-01');

create index ix_decision_user_ts  on audit_decision (user_id, ts);
create index ix_decision_service  on audit_decision (calling_service, ts);

create table audit_change (
  id         bigint generated always as identity,
  ts         timestamptz not null default now(),
  actor      text not null,
  entity     text not null,
  entity_ref text not null,
  action     text not null,
  detail     jsonb,
  primary key (id, ts)
) partition by range (ts);

create table audit_change_default partition of audit_change default;

create table audit_change_2026_10 partition of audit_change for values from ('2026-10-01') to ('2026-11-01');

create index ix_change_entity_ts on audit_change (entity, entity_ref, ts);
create index ix_change_actor     on audit_change (actor, ts);
