-- V4__grant_provenance.sql — R1 (ADR-002, team decision 2026-10-16)
-- Provenance of LIVE grants: "who granted this still-active grant, and when?"
-- must be answerable from the model itself, even after the change audit rolls
-- off its (1-year) window. One column pair on each live-grant table:
--   profile_permission · profile_scope · profile_template
--
-- granted_at defaults to the DB clock (gotcha #21: the DB clock is the source
-- of truth); granted_by is set by AdminService on each grant mutation
-- (re-grant refreshes both — the row reflects the CURRENT grant's origin).
-- These are plain table columns (not part of the PK/unique key), so the
-- Hibernate @ManyToMany mappings are unaffected; the service stamps them via
-- a targeted UPDATE in the same transaction.

alter table profile_permission add column granted_at timestamptz not null default now();
alter table profile_permission add column granted_by text;

alter table profile_scope add column granted_at timestamptz not null default now();
alter table profile_scope add column granted_by text;

alter table profile_template add column granted_at timestamptz not null default now();
alter table profile_template add column granted_by text;
