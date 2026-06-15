# Postgres Performance Agent

An agent that connects to a real Postgres database, finds the queries costing you the most,
diagnoses **why** using actual execution plans and real data distribution, classifies whether the
root cause lives in the **database** or in the **application/ORM layer**, proposes a fix at the
right layer, and (once fully built) proves database-side fixes work by measuring before/after on
the real database. It loops query-by-query until there's nothing left worth optimizing.

## What it does

A lot of slow Postgres — especially under Spring Boot / Hibernate — isn't a missing index. It's
the application using the database inefficiently: N+1 query storms, deep `OFFSET` pagination,
implicit type casts that silently disable indexes, unbounded result sets. These leave recognizable
fingerprints in `pg_stat_statements` and in execution plans, so the agent can diagnose them from
**the database's telemetry alone**, without access to the application code.

Every finding is classified before any fix is proposed:

| Classification | Meaning | Fix layer | Verifiable by the agent? |
|---|---|---|---|
| `DB_PROBLEM` | The query is reasonable; the database is under-equipped (missing/wrong index, stale stats, bad plan) | DDL / config | Yes — HypoPG + benchmark |
| `APP_PROBLEM` | The database is doing exactly what it was asked; the application is asking badly (ORM misuse) | Java / JPA code | No — reported with evidence, marked unverifiable |
| `MIXED` | Both (e.g. an N+1 that's also missing an FK index) | Both | Partially |

So when the right answer is a code change, the agent says so and points at it, instead of
proposing an index that wouldn't help.

## The one principle

The LLM never produces a number. Every metric — execution time, call counts, row counts, plan
cost — comes from real query execution and real Postgres statistics. The model's only job is to
*reason* over that real evidence: read the plan, classify the pathology, form a hypothesis, decide
the next action. Deterministic code does all measurement. That separation is what makes the output
trustworthy.

## ORM-pathology detection — how it works without touching the ORM

The agent never connects to the application. It detects application-layer problems entirely from
database-side signals. These are the pathologies it looks for, with their detection signatures:

### 1. N+1 query storms

**Symptom-side signature** (in `pg_stat_statements`):

- A parameterized single-row lookup by foreign key, e.g.
  `SELECT ... FROM order_items WHERE order_id = $1`
- `calls` is enormous (tens of thousands to millions)
- `mean_exec_time` is tiny (sub-millisecond) — this query never appears in a "slowest queries by
  mean time" report, which is exactly why humans miss it
- `total_exec_time` is among the highest in the system
- Often co-occurs with a "parent" query (`SELECT ... FROM orders WHERE ...`) whose `calls` count
  is roughly `1/N` of the child's
  **Diagnosis the LLM produces:** a lazy-loaded collection (`FetchType.LAZY`) iterated in a loop —
  each parent entity triggers one child query.

**Fix layer:** application. Recommend a fetch join (`JOIN FETCH` in JPQL), `@EntityGraph`, or a
projection/DTO query. **Not** an index — the lookup is already indexed and fast; the problem is
that it runs N times.

### 2. Implicit type-cast disabling an index

**Symptom-side signature** (in the `EXPLAIN (ANALYZE, BUFFERS)` plan + catalog):

- Plan shows `Seq Scan` with a filter like `((id)::text = $1)` or `(external_ref)::varchar = $1`
- `inspect_table` confirms a usable index exists on that exact column
- The cast in the filter is what prevents the planner from using it
  **Diagnosis:** the application is binding the wrong Java type — typically a `String` parameter
  against a `uuid` / `bigint` / `numeric` column (a classic JPA/JDBC mistake).

**Fix layer:** application (bind the correct type). A DDL workaround (expression index on
`(id::text)`) exists but the agent should present it as the inferior option and say why.

### 3. Deep OFFSET pagination

**Symptom-side signature:**

- Query text contains `LIMIT $1 OFFSET $2`
- Plan shows rows scanned/removed vastly exceeding rows returned
  (e.g. `rows=50` returned but the node beneath processed 500,000)
- `pg_stat_statements` shows mean time growing over the retention window as users page deeper
  **Diagnosis:** Spring Data `Pageable` offset pagination on a large table.

**Fix layer:** application — keyset (seek) pagination (`WHERE (created_at, id) < ($1, $2) ORDER BY
created_at DESC, id DESC LIMIT $3`), plus the supporting composite index (this part *is*
DB-verifiable, so a `MIXED` finding).

### 4. Unbounded / oversized result sets

**Symptom-side signature:**

- `pg_stat_statements.rows / calls` is huge (thousands of rows returned per call) for a query
  whose shape suggests it backs an API or UI path
- No `LIMIT` in the query text
  **Diagnosis:** missing pagination, or entity-fetch where a projection of 2–3 columns was needed.

**Fix layer:** application. No index fixes returning 50,000 rows.

### 5. Leading-wildcard searches

**Symptom-side signature:**

- Filter of the form `column LIKE '%' || $1 || '%'` (or `%...%` literal) with a `Seq Scan`
- A btree index on the column exists but is unusable for leading wildcards
  **Diagnosis:** a "contains" search field passed straight into a JPA `Containing` /
  `LIKE %:term%` query.

**Fix layer:** `MIXED` — recommend `pg_trgm` + GIN index (DB-verifiable with HypoPG-style
re-EXPLAIN), and note the application-side alternative (full-text search / dedicated search path)
for high-volume cases.

### Recognizing ORM-generated SQL

The classifier gets one extra hint: Hibernate-generated SQL has a recognizable shape — generated
aliases (`o1_0`, `oi1_0`), every column enumerated, characteristic join nesting. The LLM uses this
to justify reasoning about application-layer causes. It's a signal, not proof, and findings say so.

### Reliability: correlation, confidence, and a cross-check

Three things keep the application-layer classification trustworthy rather than a one-shot guess:

- **Parent/child correlation.** The perceive step hands the classifier the other frequent queries
  in the window, so an N+1 is confirmed by finding the *parent* query that drives it (a child
  lookup whose `calls` ≈ parent calls × rows-per-parent), not by a single query's stats in
  isolation. With a matching parent the finding is HIGH confidence; without one it's only "probably
  N+1" and marked lower.
- **A confidence level on every finding** (`HIGH` / `MEDIUM` / `LOW`). HIGH when multiple
  independent signals agree; LOW when the call rests on a *sampled* parameter value rather than the
  real one — which is the honest state for `LEADING_WILDCARD` and `IMPLICIT_CAST`, since
  `pg_stat_statements` normalizes the literal away.
- **A deterministic cross-check.** After the LLM classifies, plain Java verifies the claimed signal
  actually exists in the data (e.g. an `IMPLICIT_CAST` finding really has a cast in the plan; an
  `UNBOUNDED_RESULT` really has a high rows/call). If it doesn't, the finding is downgraded to LOW
  and annotated — catching the occasional LLM overreach without overriding its judgment.

### Honesty rule for APP_PROBLEM findings

Application-layer fixes cannot be benchmarked by this agent (the fix lives in code it cannot run).
Such findings are reported with the full evidence chain (stats + plan + reasoning) and explicitly
marked **`verified: false`, `verification: not applicable — fix is application-side`**. The agent
never fabricates an expected improvement number for them. Where useful, it may state the
arithmetic implication of the evidence (e.g. "eliminating the per-row lookup removes ~500k
calls/day of measured 0.3 ms each"), clearly labeled as derived from measurements, not predicted.

## Safety — read this first

This tool connects to a database and runs queries against it.

- **Point it at a replica or a restored clone, never primary production.**
- It runs **read-only by default** (see `application.yml`). Keep it that way until Phase 3.
- **`EXPLAIN ANALYZE` actually executes the query.** Safe on `SELECT`s; on writes it performs the
  write. The `ExplainTool` stub documents how to guard this (force `ROLLBACK`, or refuse
  non-SELECTs).
- A `statement_timeout` is set on every connection so a runaway query can't hang the agent.
- Your `.env` (with the API key and DB credentials) is gitignored. Never commit it.

## Security & data privacy

The agent runs **entirely inside your infrastructure**. It connects to your Postgres directly,
and every measurement — `EXPLAIN ANALYZE`, HypoPG hypothetical-index tests, benchmarks — executes
over that local connection and never leaves your network. The only outbound traffic is the
diagnostic evidence sent to the Anthropic API so the LLM can classify each finding.

**What is sent to the LLM** (per analyzed query):

| Sent | Example |
|---|---|
| Normalized SQL (literals already stripped by `pg_stat_statements`) | `SELECT … WHERE status = $1` |
| Schema names and index DDL | `CREATE INDEX orders_pkey ON orders (id)` |
| Table sizes and row estimates | `estimated_rows=2000000  disk=240 MB` |
| Statistics counters | `calls=2000  mean=0.25ms  rows/call=1  n_distinct=4` |
| Execution plan, value-scrubbed | `Filter: (status = $1)` — structure, costs, row counts, timings |

**What is never sent by default:**

- **Row contents.** The `pg_stats` most-common values (real cell values: emails, names,
  statuses) are excluded from the LLM payload.
- **Sampled literals.** To get honest measurements, the agent re-runs queries with values
  sampled from `pg_stats`. Those values are used only on the local DB connection; any string
  literal that would appear in the plan text is replaced with its `$N` placeholder before the
  plan goes to the LLM.
- **Credentials.** DB and SMTP credentials live in `.env` (gitignored) and are never logged,
  echoed, or sent anywhere — under any setting.

With the default, what crosses the wire is roughly what you would paste into a public
Stack Overflow question: schema and metrics, no data. Per Anthropic's commercial API terms,
inputs/outputs are not used for model training by default and are retained only briefly for
abuse monitoring — but the redaction means your data values never reach the API in the first
place.

### Opt-in: `pgagent.privacy.share-data-values`

Setting `share-data-values: true` adds the two redacted items to the LLM evidence:

| What enabling shares | Example of what leaves your network |
|---|---|
| `pg_stats` most-common values per column | `mcv={pending,shipped,delivered}` — or, on a `customers` table, real emails/names |
| Sampled literals left in plan text | `Filter: (status = 'pending')` instead of `(status = $1)` |

**What that buys you:**

- **Data-skew reasoning.** The LLM can see that one value covers 90% of rows and explain *why*
  the planner picked a Seq Scan for it — instead of inferring skew indirectly from `n_distinct`.
- **Value-specific partial indexes.** Recommendations like
  `CREATE INDEX … ON orders (created_at) WHERE status = 'shipped'` require knowing the hot/rare
  value — impossible under redaction, available with sharing on.
- **Sharper root-cause narratives** that name the actual problematic value in the report.

**What it does not change:** classification accuracy for the core pathologies (N+1, deep
OFFSET, implicit cast, unbounded result, leading wildcard) — those rely on query shape,
counters, and plan structure, all of which are always sent. And regardless of the setting,
HypoPG and the benchmark validate every DB fix locally with full statistics, so a weaker
LLM hypothesis under redaction gets caught by the EVALUATE loop, not shipped to the report.

**Recommendation:** leave it `false` for anything resembling production data; set it `true`
on the demo database or schemas with no sensitive values, where the extra reasoning is free.

**Residual exposure to be aware of (both settings):** table/column names and query shapes do
go to the API — that is inherent to having an LLM read execution plans. If even schema names
are sensitive in your environment, plug a self-hosted model into the `LlmClient` interface;
nothing else in the agent needs to change.
## Quick start

Requirements: Java 21, Maven 3.9+, Docker.

```bash
# 1. Start the demo database
docker compose up -d

# 2. Configure your environment
cp .env.example .env          # edit .env — add ANTHROPIC_API_KEY and DB credentials

# 3. Run
set -a && source .env && set +a
mvn spring-boot:run
```

The workload runs automatically at DB startup — no manual warm-up step needed.
To reset to a clean state: `docker compose down -v && docker compose up -d`.

## Demo database

The demo DB is a realistic e-commerce schema seeded with enough data to make the planted
problems actually hurt:

| Table | Rows | Purpose |
|-------|------|---------|
| `customers` | 100,000 | users with `email`, `country` |
| `orders` | 2,000,000 | FK to `customers`, skewed `status` distribution |
| `order_items` | 2,000,000 | FK to `orders`, `product_id`, `qty`, `price_cents` |

Five problems are planted at startup, one per pathology class:

| Pathology | Planted query | Signal in pg_stat_statements |
|-----------|--------------|------------------------------|
| `N_PLUS_ONE` | `SELECT … FROM customers WHERE id = $1` | calls=2000, mean<1ms, rows_per_call=1 |
| `DEEP_OFFSET` | `SELECT … FROM orders ORDER BY created_at LIMIT $1 OFFSET $2` | calls=60, 130MB disk sort |
| `IMPLICIT_CAST` | `SELECT … FROM orders WHERE id::text LIKE $1` | Seq Scan despite PK index |
| `UNBOUNDED_RESULT` | `SELECT … FROM order_items JOIN orders … WHERE status=$1` | rows_per_call≈120k, no LIMIT |
| `LEADING_WILDCARD` | `SELECT … FROM customers WHERE email LIKE $1` | LIKE '%@example.com', Seq Scan |

## What the agent finds — demo results

| Issue | Detection signal | Root cause | Proposed fix | Verified |
|-------|-----------------|------------|--------------|----------|
| **Deep OFFSET pagination** | `calls=60`, `mean=120ms`, `total=7196ms` — EXPLAIN shows full 2M-row Seq Scan + 97MB external merge sort, only 20 rows returned | `OFFSET` forces Postgres to sort and discard the entire table on every page request — O(N) cost per page | DB: `CREATE INDEX ON orders(created_at, id) INCLUDE(status, total_cents)` + App: replace `OFFSET` with keyset pagination | Yes — 418ms → 125ms (3.3x) |
| **N+1 storm** | `calls=2000`, `mean=0.00ms`, `rows_per_call=1` — thousands of single-row PK lookups | ORM loading each customer individually in a loop instead of batching | App: use `JOIN FETCH` or `@BatchSize` to load the collection in one query | No — app-side fix |
| **Implicit cast killing index** | Query text contains `id::text LIKE $1` — Seq Scan on 2M rows despite `orders_pkey` btree index, `rows_per_call=111k` | Casting the column to `text` at query time breaks index sargability — Postgres cannot use the btree index on a cast expression | App: remove the `::text` cast, pass a correctly typed parameter | No — app-side fix |
| **Unbounded result set** | No `LIMIT` in query, `rows_per_call=120k`, `calls=10`, `total=916ms` — joins `order_items × orders` on a 4-value `status` column | Application fetches entire unfiltered join result into memory on every call | App: add keyset pagination `WHERE oi.id > $last_seen_id LIMIT 1000`; for batch jobs use a server-side cursor | No — app-side fix |
| **Leading wildcard search** | `LIKE '%@example.com'` in query text — Seq Scan despite btree index on `email`, `rows_per_call=100k` (entire table) | Leading `%` prevents btree index use; no result cap means the full table is returned on every call | App: add `LIMIT` and enforce a minimum non-wildcard prefix; for substring search switch to `pg_trgm` GIN index | No — app-side fix |

4 of the 5 planted problems are application-layer misuse — only one is actually fixed with an index.
The classification step is what tells those apart.

## Sample output

```
--- FIX IN THE DATABASE (1) ---
  [DEEP_OFFSET] SELECT id, status, total_cents FROM orders ORDER BY created_at …
    calls=64  mean=125.0 ms  total=8000.0 ms
    Root cause:   LIMIT/OFFSET over 2M rows — Postgres sorts and discards the
                  entire table on every page request. 130MB external merge sort.
    Proposed fix: CREATE INDEX orders_created_at_id ON orders (created_at, id)
                  INCLUDE (status, total_cents)
    Verified:     true
    Measured:     350.0 ms → 135.0 ms  (delta=215.0 ms)

--- FIX IN YOUR CODE (4) ---
  [N_PLUS_ONE] SELECT id, email, country FROM customers WHERE id = $1
    calls=2000  mean=0.0 ms  total=5.0 ms
    Root cause:   ORM issuing one query per entity in a loop.
    Proposed fix: Use JOIN FETCH or @BatchSize to batch the collection load.
    Verified:     false

  [IMPLICIT_CAST] SELECT id, customer_id, status FROM orders WHERE id::text LIKE $1
    calls=50  mean=53.0 ms  total=2650.0 ms
    Root cause:   id::text cast breaks the PK btree index — full Seq Scan on 2M rows.
    Proposed fix: Remove the cast; use a range predicate on the numeric id instead.
    Verified:     false
  ...
```

## How the loop works

This is the part that makes it an agent rather than a single prompt: it acts on a real
environment, observes the result of each action, and revises based on what it sees.

```
PERCEIVE     read query stats from pg_stat_statements          [SlowQueryTool]
             ranked by total_exec_time AND calls — not just mean time,
             so N+1 storms (fast queries called 500k times) are caught
DIAGNOSE     EXPLAIN (ANALYZE, BUFFERS) the worst candidate     [ExplainTool]
             + table size, indexes, column stats                [TableInspectionTool]
CLASSIFY     LLM reads stats + plan + facts ->
             DB_PROBLEM | APP_PROBLEM | MIXED                   [LlmClient]
HYPOTHESIZE  DB_PROBLEM  -> one concrete DDL/config fix
             APP_PROBLEM -> named pathology + code-level recommendation,
                            evidence chain attached, marked unverifiable
TEST         (Phase 2) DB fixes only: HypoPG hypothetical index, re-EXPLAIN,
             compare estimated cost
EVALUATE     if the estimate is too weak, feed it back to the LLM for a
             revised hypothesis, or discard — up to a retry limit
VERIFY       (Phase 3) DB fixes only: apply for real, benchmark,
             capture measured before/after delta; drop the index if it regressed
LOOP         next query, until a termination condition
```

The feedback edges — TEST → EVALUATE → revised HYPOTHESIZE, and VERIFY → drop-on-regression —
are what separate this from a one-shot answer: a failed hypothesis changes what the agent does next.

**Why perceive ranks by `total_exec_time` and `calls`:** ranking by mean execution time alone is
structurally blind to N+1 patterns — a 0.3 ms query called 500,000 times costs more than a
2-second query called 60 times. The perceive step surfaces both.

**Termination** (the agent always stops): nothing left above the cost threshold, OR
max-iterations reached, OR only marginal gains in recent iterations. It logs *why* it stopped.

### Time-windowed analysis (production mode)

`pg_stat_statements` counters are cumulative since the last reset, so by default the agent
analyzes everything ever recorded — a query that was slow last month but is fixed today still
ranks at the top. For recurring runs, enable snapshot diffing:

```yaml
pgagent:
  snapshot:
    enabled: true
    path: pgagent-snapshot.json
```

Each run saves the current counters to a local JSON file, and the next run analyzes only the
delta — `calls=2000` then means "2000 calls **since the previous run**", not "since the
database started". This is the same technique pganalyze's collector uses, kept client-side so
the agent stays strictly read-only (nothing is written to the database, and other consumers of
`pg_stat_statements` are unaffected). A stats reset between runs is detected automatically
(counters went backwards) and the run falls back to the cumulative numbers.

Run the agent on a schedule (cron, CI) with snapshots enabled and each email report covers
exactly one window — "what hurt since yesterday's run".

The snapshot also remembers **which queries were flagged last run**, so each report labels
every finding and tracks fixes across runs:

- **NEW** — above thresholds now, wasn't flagged last run
- **RECURRING** — flagged last run and still hurting (not fixed yet)
- **Resolved since last run** — flagged last run, no longer above thresholds (fixed, or not
  executed this window). For APP_PROBLEM findings this is the closest thing to verification
  the agent can offer: the code fix shipped, and the query storm measurably disappeared.

## Output: the findings report

Each loop iteration produces a `Finding`:

```
query            normalized SQL (from pg_stat_statements)
classification   DB_PROBLEM | APP_PROBLEM | MIXED
pathology        e.g. N_PLUS_ONE, IMPLICIT_CAST, DEEP_OFFSET, UNBOUNDED_RESULT,
                 LEADING_WILDCARD, MISSING_INDEX, STALE_STATS, ...
evidence         the actual numbers: calls, mean/total time, rows/call,
                 relevant plan nodes, existing indexes
root_cause       LLM's reasoning over the evidence (plain language)
fix              DDL statement (DB) or code-level recommendation (APP)
verified         true only when a measured before/after exists
delta            measured improvement (DB fixes, Phase 3) — never an LLM estimate
```

The end-of-run report groups findings by classification, so the output reads as:
*"here's what to fix in the database, and here's what to fix in your code — with the evidence
for both."*

## Phases

The agent works in phases. Phase 1 always runs; the rest are optional and turned on by config.

1. **Phase 1 — diagnosis + classification (always on):** perceive → EXPLAIN → classify →
   diagnose → report. Read-only and safe; APP_PROBLEM detection works fully here with no write
   access.
2. **Phase 2 — estimated before/after (optional):** HypoPG hypothetical-index testing for
   DB_PROBLEM findings. Active when the database has the HypoPG extension; still read-only.
3. **Phase 3 — apply and measure (optional, opt-in):** real benchmarking and a guarded "apply
   the change" path for DB fixes. Off by default — requires setting `pgagent.loop.apply-fixes`
   and `spring.datasource.hikari.read-only: false`. Point it at a clone, not production.

For application-layer findings, the agent stops at a precise, evidence-backed recommendation
(named pathology + code-level fix + the numbers). Applying that change in your codebase is left
to you — your own editor or coding assistant — so the agent never needs access to your source.

## Project layout

```
src/main/java/dev/gyda/pgagent/
├── PgAgentApplication.java       app entry point
├── AgentRunner.java              CommandLineRunner — starts the loop, prints grouped report
├── EmailReporter.java            optional email report after each run
├── agent/AgentLoop.java          PERCEIVE → DIAGNOSE → CLASSIFY → TEST → VERIFY loop
├── tools/
│   ├── SlowQueryTool.java        reads pg_stat_statements, deduplicates, ranks by total+calls
│   ├── ExplainTool.java          EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON), SELECT-only guard
│   ├── TableInspectionTool.java  table size, indexes, column stats
│   ├── HypoPGTool.java           Phase 2 — hypothetical index cost estimate
│   ├── BenchmarkTool.java        Phase 3 — N-run mean/min/max timing
│   └── ApplyTool.java            Phase 3 — guarded DDL apply (opt-in via config)
├── llm/
│   ├── LlmClient.java            interface
│   ├── AnthropicLlmClient.java   Messages API implementation
│   └── Prompts.java              all prompt strings in one place
├── model/                        SlowQuery, Finding, Classification, Pathology,
│                                 HypoResult, BenchmarkResult, TableStats, ColumnStat
└── config/AgentProperties.java   typed pgagent.* settings

db/init/01-init.sql               schema + 100k customers + 2M orders + ANALYZE
db/init/02-workload.sql           plants all 5 pathologies via EXECUTE…USING loops
db/init/03-grants.sql             pg_read_all_stats for the pgagent user
db/RUNBOOK.md                     reset instructions, pathology table, verify query
docker-compose.yml                demo Postgres with pg_stat_statements enabled
```

## Similar tools

For context, here are related tools and how this project overlaps or differs:

| Tool | What it does | Relationship to this project |
|---|---|---|
| **Dexter / pganalyze Index Advisor** | Suggest indexes via hypothetical (HypoPG) indexes | Same HypoPG technique for the DB-side check; here it's one step in the loop, not the whole tool |
| **pganalyze / pgMustard** | Continuous monitoring and plan visualization | Strong at watching a database over time; they don't classify DB-vs-app or apply-and-measure fixes |
| **DBtune / OtterTune** | Tune server parameters (`shared_buffers`, etc.) | Different layer — server config, not individual queries or app behavior |
| **Hypersistence Optimizer, Digma, APM N+1 detectors** | Catch ORM misuse from inside the running app | Same pathologies, opposite direction — they instrument the application; this reads the database telemetry |
| **Generic "AI DBA" chat tools** | Give tuning advice from a prompt | Don't connect to real plans or measure anything |

Where this project sits: it classifies whether the root cause is the database or the application,
diagnoses ORM misuse from database telemetry alone, and verifies database-side fixes by measuring
them. That combination is its focus.

## License

MIT — see `LICENSE`.