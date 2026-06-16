# Example run

A walkthrough of the agent against the bundled demo database, so you can see what it produces
before pointing it at anything of your own. The demo plants one problem per pathology class, and
every number below is from a **real run** (`claude-sonnet-4-6`, Phase 3 enabled).
The whole run took **~1.5 minutes** (Maven reported `01:32 min`) and
cost **$0.05** in Anthropic API usage — one classification call per candidate plus one revision (5
calls total). Most of the elapsed time is the five LLM round-trips (~15–20s each) plus the Phase 3
`CREATE INDEX CONCURRENTLY` and benchmark; the Postgres diagnostics themselves are milliseconds. The
LLM never produced a single metric; all numbers come from `pg_stat_statements`, `EXPLAIN`, HypoPG,
and the Phase 3 benchmark.

### What was enabled for this run

| Setting | Value | Effect |
|---|---|---|
| `pgagent.loop.apply-fixes` | `true` | Phase 3 on — agent may apply `CREATE INDEX` and benchmark it |
| `spring.datasource.hikari.read-only` | `false` | required companion to `apply-fixes` so writes are accepted |
| `pgagent.snapshot.enabled` | `true` | time-windowed analysis — "since last run" (see the snapshot file below) |
| `…hikari.data-source-properties.preferQueryMode` | `extendedForPrepared` | lets `EXPLAIN (GENERIC_PLAN)` plan queries with unbound `$N` over the simple protocol |
| `pgagent.report.recipient-email` | set | HTML email report sent at the end of the run |

Everything else is at its default (`min-mean-time-ms=50`, `min-total-time-ms=500`,
`min-estimated-speedup=1.5`, `min-measured-speedup=1.1`, `max-retries-per-query=2`).

## The demo database

A realistic e-commerce schema seeded with **~4.1 million rows** — enough to make the planted
problems actually hurt (a Seq Scan over 10k rows is too cheap to diagnose). Schema and seed live in
[`db/init/01-init.sql`](../db/init/01-init.sql):

| Table | Rows | Notes |
|-------|------|-------|
| `customers` | 100,000 | `email` (`userN@example.com`), `country` skewed ~85% `SA` |
| `orders` | 2,000,000 | `customer_id` **deliberately not indexed**, `status` skewed ~90% `completed`, `total_cents`, `created_at` |
| `order_items` | 2,000,000 | `order_id`, `product_id` (1–10k), `qty` (1–5), `price_cents` |

Only the primary keys are indexed; `orders.customer_id` and the sort/filter columns are left bare
on purpose so the pathologies surface. `pg_stat_statements` is reset after seeding, so every number
the agent sees comes from the workload, not the data load.

Five problems are then planted by the workload in
[`db/init/02-workload.sql`](../db/init/02-workload.sql) — one per pathology. The loop counts there
are exactly the `calls` the agent reports:

| Pathology | Planted query (verbatim from the workload) | Reps | Signal in `pg_stat_statements` |
|-----------|--------------------------------------------|------|------------------------------|
| `N_PLUS_ONE` | `SELECT id, email, country FROM customers WHERE id = $1` | 2000 | calls=2000, mean<1ms, rows/call=1 |
| `DEEP_OFFSET` | `SELECT id, status, total_cents FROM orders ORDER BY created_at LIMIT $1 OFFSET $2` | 60 | calls=60 (offsets 50k & 100k), external merge sort to disk |
| `IMPLICIT_CAST` | `SELECT id, customer_id, status FROM orders WHERE id::text LIKE '10%'` | 50 | calls=50, Seq Scan despite PK index |
| `UNBOUNDED_RESULT` | `SELECT oi.id, oi.order_id, oi.product_id FROM order_items oi JOIN orders o … WHERE o.status = 'pending'` | 10 | calls=10, rows/call≈120k, no LIMIT |
| `LEADING_WILDCARD` | `SELECT id, email FROM customers WHERE email LIKE '%@example.com'` | 100 | calls=100, Seq Scan |

## What the agent found

This run surfaced **4 candidates** above the thresholds (`mean ≥ 50 ms OR total ≥ 500 ms`). The
N+1 storm query (`customers WHERE id = $1`) stayed *below* the 500 ms total-time bar this window —
2000 sub-millisecond calls summed to only a few ms — so it was not selected as a candidate. (Lower
`min-total-time-ms` to surface it; it's a deliberate threshold trade-off, not a miss.)

| Issue | Detection signal | Classification | Root cause | Proposed fix | Verified |
|-------|-----------------|----------------|------------|--------------|----------|
| **Deep OFFSET pagination** | `calls=60`, `mean=123.9ms`, `total=7435.2ms` — full 2M-row parallel Seq Scan + external merge sort spilling to disk (24,306 temp blocks written), only 50 rows returned | `MIXED` / `DEEP_OFFSET` (HIGH) | `OFFSET` over 2M rows with no index on the sort key forces a full scan + sort on every page; later pages cost more | DB: `CREATE INDEX orders(created_at, id)` + App: keyset pagination | **Yes** — applied & measured: 176.1ms → 10.1ms (**17.5×**); HypoPG est. 25.6× |
| **Cast query → unbounded result** | `calls=50`, `mean=58.0ms`, `total=2901.4ms`, `rows/call=111k` (5.5% of table) | first `DB`/`IMPLICIT_CAST` (LOW) → **revised** to `APP`/`UNBOUNDED_RESULT` (HIGH) | HypoPG showed a functional index on `(id::text)` gives **1.0×** — at 5.5% selectivity a Seq Scan is correct. The real fault is fetching 111k rows/call with no LIMIT | App: add `LIMIT` + keyset pagination; require a longer prefix | No — app-side fix |
| **Unbounded join** | `calls=10`, `mean=95.9ms`, `total=959.1ms`, `rows/call=120k` — `status` has `n_distinct=4`, ~90% of rows match; hash join spills to disk | `MIXED` / `UNBOUNDED_RESULT` (HIGH) | App pulls ~1.8M rows into memory per call; a `status` btree is too unselective to help until the query is paginated | App: keyset pagination (primary); supporting indexes once paginated (secondary) | No — app-side fix |
| **Leading wildcard search** | `calls=100`, `mean=8.0ms`, `total=800.7ms`, `rows/call=100k` — Seq Scan, `Filter: (email ~~ $1)`, no index on `email` | `DB` / `LEADING_WILDCARD` (LOW) | Leading `%` prevents btree use; needs a trigram index for substring search | DB: `pg_trgm` GIN index on `email` | No — HypoPG can't model GIN, and the multi-statement DDL is outside `ApplyTool`'s `CREATE INDEX`-only allowlist |

Only one of the four is actually fixed with an index — and it's the one the agent could **measure**
(17.5× faster, applied with `CREATE INDEX CONCURRENTLY` and kept because it cleared the bar). The
classification step is what tells the database fix apart from the application-layer ones.

### The agentic moment: a discarded hypothesis

The second candidate (`orders WHERE id::text LIKE $1`) is the clearest demonstration of why this is
an agent and not a linter. The LLM's first guess was the textbook one — `IMPLICIT_CAST`, "add a
functional index on `(id::text)`". The agent didn't take its word for it: HypoPG modelled that exact
index and reported **1.0× speedup**. Below the `1.5×` bar, so the agent fed the failure back to the
LLM and asked for a *different* hypothesis. The revised answer correctly identified the real
pathology — an `UNBOUNDED_RESULT` returning 111k rows/call — and moved the fix to the application
layer. No index was recommended for a query no index can help.

## Sample output

The agent loop trace (one pass, 4 candidates):

```
AgentRunner - === Postgres Performance Agent ===
SnapshotTool - No snapshot at pgagent-snapshot.json — this run analyzes the full pg_stat_statements history.
AgentLoop - Found 4 candidate slow queries (mean >= 50.0 ms OR total >= 500.0 ms).

AgentLoop - [1/4] DIAGNOSE: mean=123.9 ms  total=7435.2 ms  calls=60  SELECT id, status, total_cents FROM orders ORDER BY created_at LIMIT $1 OFFSET $2
AgentLoop -   CLASSIFY: MIXED / DEEP_OFFSET (confidence HIGH)  fix: DB layer — create the index: CREATE INDEX CONCURRENTLY orders_created_at_id_idx …
AgentLoop -   Phase 2: cost 144294.4 → 5631.9 (est. 25.6x)
AgentLoop -   Phase 3 before: mean=176.1 ms  min=161.8 ms  max=213.4 ms
ApplyTool -   APPLYING DDL (ensure target is a replica or clone): CREATE INDEX CONCURRENTLY orders_created_at_id_idx ON public.orders (created_at, id)
ApplyTool -   DDL applied successfully.
AgentLoop -   Phase 3 after:  mean=10.1 ms  min=9.3 ms  max=10.8 ms  (delta=166.0 ms, 17.5x)

AgentLoop - [2/4] DIAGNOSE: mean=58.0 ms  total=2901.4 ms  calls=50  SELECT id, customer_id, status FROM orders WHERE id::text LIKE $1
AgentLoop -   Params unresolvable — GENERIC_PLAN only, Phase 3 unavailable for this query.
AgentLoop -   CLASSIFY: DB_PROBLEM / IMPLICIT_CAST (confidence LOW)  fix: CREATE INDEX orders_id_text_idx ON public.orders USING btree ((id::text) text_pattern_ops);
AgentLoop -   Phase 2: cost 35130.3 → 35130.3 (est. 1.0x)
AgentLoop -   EVALUATE: est. 1.0x < required 1.5x — requesting revised hypothesis (1/2).
AgentLoop -   REVISED: APP_PROBLEM / UNBOUNDED_RESULT  fix: Add pagination to the query: apply a LIMIT … and use keyset pagination on the `id` column …

AgentLoop - [3/4] DIAGNOSE: mean=95.9 ms  total=959.1 ms  calls=10  SELECT oi.id, oi.order_id, oi.product_id FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE …
AgentLoop -   CLASSIFY: MIXED / UNBOUNDED_RESULT (confidence HIGH)  fix: Application fix (primary): Introduce keyset/cursor-based pagination …

AgentLoop - [4/4] DIAGNOSE: mean=8.0 ms  total=800.7 ms  calls=100  SELECT id, email FROM customers WHERE email LIKE $1
AgentLoop -   CLASSIFY: DB_PROBLEM / LEADING_WILDCARD (confidence LOW)  fix: CREATE EXTENSION IF NOT EXISTS pg_trgm; CREATE INDEX idx_customers_email_trgm … USING gin (email gin_trgm_ops);
AgentLoop -   HypoPG failed: ERROR: hypopg: access method "gin" is not supported
AgentLoop -   Phase 3 before: mean=6.4 ms  min=4.9 ms  max=9.0 ms
AgentLoop -   Phase 3 failed: ApplyTool only executes CREATE [UNIQUE] INDEX statements. Got: CREATE EXTENSION IF NOT EXISTS pg_trgm; …
AgentLoop - Loop complete — 4 finding(s). Stopped: all candidates processed.
```

The grouped report (root-cause / evidence text abbreviated here — the full text is in the log and email):

```
--- FIX IN THE DATABASE (3) ---
  [DEEP_OFFSET] SELECT id, status, total_cents FROM orders ORDER BY created_at LIMIT $1 OFFSET $2  calls=60  mean=123.9 ms  total=7435.2 ms
    Confidence:   HIGH
    Root cause:   OFFSET pagination over 2M rows, no index on created_at — full parallel Seq Scan
                  (~148 MB) + external merge sort spilling to disk, to return 50 rows.
    Proposed fix: DB — CREATE INDEX CONCURRENTLY orders_created_at_id_idx ON public.orders (created_at, id);
                  App — replace OFFSET with keyset pagination on (created_at, id).
    Verified:     true
    Hypo test:    cost 144294.4 → 5631.9 (est. 25.6x)
    Measured:     176.1 ms → 10.1 ms  (delta=166.0 ms)

  [UNBOUNDED_RESULT] SELECT oi.id, oi.order_id, oi.product_id FROM order_items oi JOIN orders o … calls=10  mean=95.9 ms  total=959.1 ms
    Confidence:   HIGH
    Root cause:   rows/call=120,252, no LIMIT; status n_distinct=4 (~90% match) so a btree is too
                  unselective. App pulls ~1.8M rows into memory per call; hash join spills to disk.
    Proposed fix: App (primary) keyset pagination; supporting indexes (secondary) once paginated.
    Verified:     false

  [LEADING_WILDCARD] SELECT id, email FROM customers WHERE email LIKE $1  calls=100  mean=8.0 ms  total=800.7 ms
    Confidence:   LOW
    Root cause:   No index on email; a leading-wildcard LIKE can't use a btree anyway. Sampled
                  param returned 1 row, so confidence is LOW (production pattern unknown).
    Proposed fix: CREATE EXTENSION pg_trgm; CREATE INDEX … USING gin (email gin_trgm_ops);
    Verified:     false   (HypoPG can't model GIN; DDL outside ApplyTool's CREATE INDEX allowlist)

--- FIX IN YOUR CODE (3) ---
  [DEEP_OFFSET]      … (MIXED — also listed above, with the measured DB fix)
  [UNBOUNDED_RESULT] SELECT id, customer_id, status FROM orders WHERE id::text LIKE $1  calls=50  mean=58.0 ms  total=2901.4 ms
    Confidence:   HIGH
    Root cause:   rows/call=111,111 (5.5% of table), no LIMIT. HypoPG confirmed a functional index
                  gives 1.00x — Seq Scan is correct at this selectivity. The fault is the unbounded result.
    Proposed fix: Add a LIMIT and keyset pagination on id; require a longer prefix, or move to a batch job.
    Verified:     false
  [UNBOUNDED_RESULT] … (the order_items × orders join, MIXED — also listed above)
```

> `MIXED` findings appear in **both** sections by design: the deep-OFFSET case needs the index
> *and* the keyset-pagination code change, so it shows up under "fix in the database" (with the
> measured index result) and under "fix in your code" (with the pagination recommendation).

## The email report

The same run is delivered as an HTML email (when `REPORT_RECIPIENT_EMAIL` and mail creds are set),
with per-finding cards, confidence badges, and the measured before/after. The actual email from
this run is included in the repo: **[email-report.pdf](email-report.pdf)**.

## The snapshot file

With `snapshot.enabled: true`, the run writes the cumulative `pg_stat_statements` counters to
`pgagent-snapshot.json` so the *next* run analyzes only the activity in between. It's deliberately
client-side — the agent never writes counters back to the database. This is what the run produced:

```json
{
  "takenAtEpochMs": 1781613342489,
  "queries": {
    "select id, status, total_cents from orders order by created_at limit $1 offset $2":
      { "calls": 60,   "totalTimeMs": 7435.21,  "rows": 1200 },
    "select id, customer_id, status from orders where id::text like $1":
      { "calls": 50,   "totalTimeMs": 2901.41,  "rows": 5555550 },
    "select oi.id, oi.order_id, oi.product_id from order_items oi join orders o on o.id = oi.order_id where o.status = $1":
      { "calls": 10,   "totalTimeMs": 959.12,   "rows": 1202520 },
    "select id, email from customers where email like $1":
      { "calls": 100,  "totalTimeMs": 800.68,   "rows": 10000000 },
    "select id, email, country from customers where id = $1":
      { "calls": 2000, "totalTimeMs": 1.81,     "rows": 2000 }
  },
  "reportedQueries": [
    "select id, status, total_cents from orders order by created_at limit $1 offset $2",
    "select id, customer_id, status from orders where id::text like $1",
    "select oi.id, oi.order_id, oi.product_id from order_items oi join orders o on o.id = oi.order_id where o.status = $1",
    "select id, email from customers where email like $1"
  ]
}
```

Two things this file makes concrete:

- **Why the N+1 didn't surface.** The last entry — the `customers WHERE id = $1` storm — has
  `calls: 2000` but `totalTimeMs: 1.81`. 2000 sub-millisecond calls sum to under 2 ms, far below the
  500 ms `min-total-time-ms` bar, so it was snapshotted but not reported (it's absent from
  `reportedQueries`). Lower the threshold to surface it.
- **`reportedQueries` drives the "Resolved since last run" line.** It records the 4 candidates this
  run flagged; if a later run no longer sees them above threshold (because you shipped the fix, or
  the workload was idle), they're reported as resolved — the only signal the agent has that an
  application-side fix actually landed.

## Reproduce it

```bash
docker compose up -d --build      # demo Postgres with pg_stat_statements + HypoPG
cp .env.example .env              # add your ANTHROPIC_API_KEY (and mail creds for the email report)
set -a && source .env && set +a
# wait for the DB to finish seeding (~2M rows) before the agent connects:
until docker compose exec -T db pg_isready -U pgagent -d pgagent_demo >/dev/null 2>&1; do sleep 1; done
mvn spring-boot:run
```

Reset to a clean state with `docker compose down -v && rm -f pgagent-snapshot.json && docker compose
up -d --build`. The `rm` matters: the snapshot file is client-side and survives the volume wipe — if
you leave it, the next run diffs the fresh workload against a stale baseline and finds **0 candidates**
(set `snapshot.enabled: false` for one-shot demos to avoid this entirely).
