# RUNBOOK — audit partition rotation (roll-forward + 3-month retention)

> Scope: the monthly `audit_decision` / `audit_change` partitions (PostgreSQL,
> created by `V2__audit.sql`; design §5.3, ADR D8).
> Retention policy: **rolling 3 months** (current month + the two previous),
> team-confirmed 2026-10-14.
> Rotation script: [`ops/rotate_audit_partitions.sql`](ops/rotate_audit_partitions.sql)
> — **idempotent**, safe to run repeatedly (cron or manual), verified against
> PostgreSQL 16.15 (roll-forward, retention, idempotency, default-partition
> safety net).

---

## 1. Why this exists

- The audit tables are `RANGE`-partitioned by month on `ts`. PostgreSQL does
  **not** create future partitions for you: if next month's partition is missing
  when the month starts, **every audit row for that month lands in the
  `*_default` partition**.
- Writes still succeed in that case (the default partition is the safety net —
  verified: an out-of-range row routes to `audit_decision_default`), but the
  default partition then grows unbounded, partition pruning degrades for those
  rows, and the retention step below **never drops it** (it is deliberately
  never touched).
- Retention: dropping a monthly partition is a **fast, metadata-only**
  `DROP TABLE` (the pages are unlinked, not deleted row-by-row) — this is why
  partitioning was chosen: 3-month retention is a cheap `DROP`, not a `DELETE`.

## 2. The monthly procedure (manual)

Run the rotation script — it does both jobs:

```bash
psql "$DB_URL" -v ON_ERROR_STOP=1 -f ops/rotate_audit_partitions.sql
```

What it does (see the script header for the full contract):

1. **Roll-forward** — for each of `audit_decision` and `audit_change`, creates
   next month's partition if it does not exist yet:
   `roll-forward: created audit_decision_2026_11` (NOTICE, once per month).
2. **Retention** — drops monthly partitions strictly older than
   `current_month − 2 months` (lexicographic `YYYY_MM` comparison, year-safe):
   `retention: dropped audit_decision_2026_08` (NOTICE per drop).

**Silent output = nothing to do** (idempotent). Suggested timing: around the
**25th of each month** — creates next month's partition a week early and drops
the month that just left the window. A **daily** run is also safe (a cheap
no-op outside the monthly window) if you prefer zero manual steps.

### Optional: archive a month before it is dropped

The team decision is **drop, no archive** (2026-10-14). If compliance later
requires an archive, dump the partition **before** running the rotation:

```bash
pg_dump "$DB_URL" -t audit_decision_2026_08 -Fc -f archive/audit_decision_2026_08.dump
pg_dump "$DB_URL" -t audit_change_2026_08   -Fc -f archive/audit_change_2026_08.dump
```

(`-t <partition>` dumps that partition as its own table; the parent is not
included. Restore with `pg_restore` into any database with the parent table.)

## 3. Automation (example cron)

```cron
# 03:00 on the 25th of each month — roll-forward + retention (idempotent)
0 3 25 * * psql "$DB_URL" -v ON_ERROR_STOP=1 -f /opt/rbac/ops/rotate_audit_partitions.sql >> /var/log/rbac-partition-rotate.log 2>&1
```

Use the role that **owns the audit tables** (the one that applied
`V2__audit.sql`) — it needs `CREATE` + `DROP` on the schema (`public`).

## 4. Monitoring

**Alert if the default partitions are non-empty** (roll-forward miss or
out-of-range `ts`):

```sql
select relname, n_live_tup
  from pg_stat_user_tables
 where relname in ('audit_decision_default', 'audit_change_default');
-- n_live_tup > 0 → investigate (n_live_tup is an estimate, but 0 vs. non-0 is reliable enough)
```

**Alert if next month's partition is missing** (should never fire with the
monthly job; belt and suspenders):

```sql
select p.relname || '_' || to_char(date_trunc('month', now())::date + interval '1 month', 'YYYY_MM') as expected,
       count(c.oid)                                          as present
  from pg_class p
  left join pg_inherits i   on i.inhparent = p.oid
  left join pg_class c      on c.oid = i.inhrelid
 where p.relname in ('audit_decision', 'audit_change')
 group by 1;
-- present = 0 for either parent → roll-forward miss
```

**Partition inventory + sizes** (verify after each rotation):

```sql
select c.relname, pg_size_pretty(pg_total_relation_size(c.oid)) as size
  from pg_class c
  join pg_namespace n  on n.oid = c.relnamespace
  join pg_inherits i   on i.inhrelid = c.oid
 where n.nspname = current_schema()
   and i.inhparent in (select oid from pg_class
                        where relname in ('audit_decision', 'audit_change'))
 order by 1;
```

## 5. Failure modes & recovery

| Symptom | Cause | Recovery |
|---|---|---|
| `*_default` partition has rows | Roll-forward was missed for some month | 1) Create the missing month's partition: `create table audit_decision_2026_11 partition of audit_decision for values from ('2026-11-01') to ('2026-12-01');` (same for `audit_change`). **New partitions do NOT pull in existing rows** — 2) move the stranded rows: `insert into audit_decision select * from audit_decision_default where ts >= '2026-11-01' and ts < '2026-12-01'; delete from audit_decision_default where ts >= '2026-11-01' and ts < '2026-12-01';` (repeat per month/partition). |
| Rotation script errors on permissions | Run as a role without `CREATE`/`DROP` on the schema | Use the table-owning role (see §3). |
| A month was dropped too early (human error outside the script) | `DROP TABLE` on a partition is **irreversible** | Only an archive dump (if one exists, §2) restores it. This is why the script restricts drops to `<parent>_<YYYY_MM>` partitions of the two audit parents, strictly older than the 3-month window, and prints a NOTICE per drop — read the output. |
| `ERROR: partition ... conflicts with existing partition` | A manual/legacy partition with the same name or overlapping range exists | List partitions (§4), align the ranges, re-run. |

## 6. Verification checklist (after each run)

- [ ] Expected NOTICEs were printed (creation / drops) — or silence (no-op).
- [ ] Exit code 0 (`ON_ERROR_STOP=1`).
- [ ] Inventory query (§4) shows: current month, next month, and the two
      previous months — for **both** parents — plus both `*_default`.
- [ ] Both `*_default` partitions still exist and are empty (§4 alert query).
- [ ] Optional: probe row for next month routes to the new partition:
      `insert into audit_decision (ts, user_id, profile_set, op) values
      ('<first day of next month> 00:00:00+00','probe','{probe}','probe')
      returning tableoid::regclass::text;` → expect
      `audit_decision_<YYYY_MM>`; then delete the probe row from that
      partition.

---

*Created 2026-10-15 (handoff §11 item 5). The script was verified on
PostgreSQL 16.15: roll-forward, retention drop, idempotent re-run, and the
default-partition safety net. Note: an earlier verification pass was a false
positive — `docker exec` heredocs without `-i` feed psql an empty stdin and
exit 0 (AGENTS.md gotcha #20); re-testing with guaranteed stdin confirmed the
script's behavior above.*
