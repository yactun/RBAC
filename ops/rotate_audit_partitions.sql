-- ops/rotate_audit_partitions.sql
-- Audit partition rotation: monthly roll-forward + SPLIT retention
-- (ADR D8 2026-10-14; ADR-002 R1 2026-10-16 — retention split confirmed by
-- the team: SOX/ISO 27001 norm for change records).
--
-- What it does (idempotent — safe to run repeatedly, e.g. from cron):
--   1. ROLL-FORWARD: for audit_decision and audit_change, create next month's
--      monthly partition if it does not exist yet.
--   2. RETENTION:    drop monthly partitions strictly older than each table's
--      rolling window:
--        audit_decision → 3 months  (high volume; current + 2 previous months)
--        audit_change   → 12 months (compliance floor; "who granted this
--                                active grant, and when?" must stay answerable)
--      The *_default partitions are NEVER touched (they are the overflow
--      safety net). If compliance raises the floor (e.g. 7 years for some
--      SOX scopes), change RETENTION_MONTHS['audit_change'] only.
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

-- 2) Retention: drop monthly partitions strictly older than each table's window
--    (ADR-002 R1): audit_decision = 3 months, audit_change = 12 months.
--    Only tables whose name ends in _YYYY_MM and that are actual partitions of
--    the two audit parents are considered; *_default is excluded.
DO $$
DECLARE
    cutoff  text;
    rec     record;
    win     int;
    windows text[] := ARRAY['audit_decision=3', 'audit_change=12'];
BEGIN
    FOR rec IN
        SELECT c.relname, par.relname AS parent
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
          JOIN pg_inherits i  ON i.inhrelid = c.oid
          JOIN pg_class par   ON par.oid = i.inhparent
         WHERE n.nspname = current_schema()
           AND par.relname IN ('audit_decision', 'audit_change')
           AND c.relname <> par.relname || '_default'
           AND substring(c.relname from '[0-9]{4}_[0-9]{2}$') IS NOT NULL
    LOOP
        win := (SELECT split_part(w, '=', 2)::int
                  FROM unnest(windows) w
                 WHERE split_part(w, '=', 1) = rec.parent);
        IF win IS NULL THEN win := 3; END IF;  -- safe default: the shorter window
        cutoff := to_char(date_trunc('month', now())::date - (win - 1) * interval '1 month',
                          'YYYY_MM');
        IF substring(rec.relname from '[0-9]{4}_[0-9]{2}$') < cutoff THEN
            EXECUTE format('DROP TABLE %I', rec.relname);
            RAISE NOTICE 'retention: dropped % (window: % months)', rec.relname, win;
        END IF;
    END LOOP;
END
$$;
