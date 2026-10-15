-- ops/rotate_audit_partitions.sql
-- Audit partition rotation: monthly roll-forward + 3-month retention
-- (ADR D8 — retention confirmed by the team 2026-10-14).
--
-- What it does (idempotent — safe to run repeatedly, e.g. from cron):
--   1. ROLL-FORWARD: for audit_decision and audit_change, create next month's
--      monthly partition if it does not exist yet.
--   2. RETENTION:    drop monthly partitions strictly older than the rolling
--      3-month window (current month + the two previous months). The
--      *_default partitions are NEVER touched (they are the overflow safety net).
--
-- Usage:
--   psql "$DB_URL" -v ON_ERROR_STOP=1 -f ops/rotate_audit_partitions.sql
--
-- Notes:
--   - Needs CREATE + DROP privileges on the schema holding the audit tables
--     (public by default), e.g. the `access` application role.
--   - DROP TABLE on a partition is a fast, metadata-only operation (the data
--     pages are unlinked, not row-by-row deleted) — retention of a large month
--     does not lock the table.
--   - If you want to ARCHIVE a month before it is dropped, pg_dump the
--     partition first (see RUNBOOK-audit-partitions.md §5); this script does
--     not archive.
--   - Runs against the schema resolved by the session search_path
--     (current_schema()); the app uses `public`.

\set ON_ERROR_STOP on

-- 1) Roll forward: ensure next month's partition exists for each audit parent.
DO $$
DECLARE
    m_start date := date_trunc('month', now())::date + interval '1 month';
    m_end   date := m_start + interval '1 month';
    p       text;
    part    text;
BEGIN
    FOR p IN SELECT * FROM unnest(ARRAY['audit_decision', 'audit_change']) LOOP
        part := p || '_' || to_char(m_start, 'YYYY_MM');
        IF NOT EXISTS (
            SELECT 1
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = current_schema() AND c.relname = part
        ) THEN
            EXECUTE format(
                'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                part, p, m_start, m_end);
            RAISE NOTICE 'roll-forward: created %', part;
        END IF;
    END LOOP;
END
$$;

-- 2) Retention: drop monthly partitions strictly older than the 3-month window.
--    Only tables whose name ends in _YYYY_MM and that are actual partitions of
--    the two audit parents are considered; *_default is excluded.
DO $$
DECLARE
    cutoff text := to_char(date_trunc('month', now())::date - interval '2 months',
                           'YYYY_MM');
    rec    record;
BEGIN
    FOR rec IN
        SELECT c.relname
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_inherits i  ON i.inhrelid = c.oid
          JOIN pg_class par   ON par.oid = i.inhparent
         WHERE n.nspname = current_schema()
           AND par.relname IN ('audit_decision', 'audit_change')
           AND c.relname <> par.relname || '_default'
           AND substring(c.relname from '[0-9]{4}_[0-9]{2}$') IS NOT NULL
    LOOP
        IF substring(rec.relname from '[0-9]{4}_[0-9]{2}$') < cutoff THEN
            EXECUTE format('DROP TABLE %I', rec.relname);
            RAISE NOTICE 'retention: dropped %', rec.relname;
        END IF;
    END LOOP;
END
$$;
