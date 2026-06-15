# Demo Database Runbook

## Reset and start

```bash
docker compose down -v   # wipe volume — required to re-run init scripts
docker compose up -d     # recreates schema, seed data, and workload automatically
```

The init scripts run once in filename order the first time the volume is created:

| File | What it does |
|------|-------------|
| `01-init.sql` | Schema, 100k customers, 2M orders, ANALYZE, stats reset |
| `02-workload.sql` | `order_items` table + bad query patterns (see below) |
| `03-grants.sql` | Grants `pg_read_all_stats` to the `pgagent` user |

No manual queries needed — the workload runs automatically on `docker compose up`.

## Planted pathologies

| Pathology | Query | Calls | Expected classification |
|-----------|-------|-------|------------------------|
| N_PLUS_ONE | `SELECT … FROM customers WHERE id = $1` | 2000 | APP_PROBLEM |
| DEEP_OFFSET | `SELECT … FROM orders ORDER BY created_at LIMIT $1 OFFSET $2` | 60 | MIXED |
| IMPLICIT_CAST | `SELECT … FROM orders WHERE id::text LIKE $1` | 50 | APP_PROBLEM |
| UNBOUNDED_RESULT | `SELECT … FROM order_items JOIN orders … WHERE status = $1` | 10 | APP_PROBLEM |
| LEADING_WILDCARD | `SELECT … FROM customers WHERE email LIKE $1` | 100 | APP_PROBLEM |

## Verify the workload loaded

```bash
docker exec -it pgagent-demo-db psql -U pgagent -d pgagent_demo
```

```sql
SELECT left(query, 80), calls, round(total_exec_time::numeric, 0) AS total_ms
FROM pg_stat_statements
WHERE ltrim(query) ILIKE 'SELECT%'
ORDER BY total_exec_time DESC
LIMIT 10;
```

## Notes

- Phase 2 (HypoPG hypothetical indexes) requires the `hypopg` OS package. The standard
  `postgres:16` image does not include it. The workload script attempts to enable it and
  logs a notice if unavailable — the agent falls back to Phase 3 directly.
- Phase 3 (apply + benchmark) is controlled by `pgagent.loop.apply-fixes` in
  `application.yml`. Set to `false` for read-only diagnosis.
- To detect the N+1 pattern, set `pgagent.loop.min-total-time-ms: 1.0` — the 2000 fast
  PK lookups complete in ~5ms total, below the default 500ms threshold.
