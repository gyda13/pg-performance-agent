# Developer Guide — Postgres Performance Agent

This guide explains how the agent works internally, what each Postgres feature it uses
actually does, and what you get out of it as a developer. Read this after the README:
the README explains *what* the project is, this explains *how the code does it*.

---

## 1. The big picture

The agent automates what a senior DBA does in a performance incident review:

> pull the most expensive queries from `pg_stat_statements` → `EXPLAIN` the worst ones →
> check table sizes, indexes, and column statistics → decide whether the fix is a
> database change (index, stats) or an application change ("go fix your repository
> method") → for database fixes, prove the fix works before recommending it.

Two rules shape every line of code:

1. **The LLM never produces a number.** Every metric comes from real query execution or
   real Postgres statistics. The LLM only *reasons* over that evidence: it reads plans,
   classifies, and proposes fixes. Deterministic Java does all measurement.
2. **Application-layer findings are never "verified".** The agent cannot benchmark a Java
   code change, so APP_PROBLEM findings carry `verified = false` with the evidence chain
   attached — never a fabricated improvement number.

## 2. The pipeline, step by step

One run of the agent (`AgentRunner` starts it at boot) is a single pass over this loop,
orchestrated by `agent/AgentLoop.java`:

```
PERCEIVE → resolve params → DIAGNOSE → CLASSIFY → TEST (Phase 2) → EVALUATE → VERIFY (Phase 3) → EVALUATE → REPORT
                                          ↑                            |
                                          └── revised hypothesis ──────┘  (max-retries-per-query)
```

| Step | Class | What it actually does | Postgres feature used |
|---|---|---|---|
| **PERCEIVE** | `SlowQueryTool` + `SnapshotTool` | Pulls top queries ranked by `total_exec_time` (not mean time!), merges duplicate entries for the same normalized query, optionally diffs against the previous run's snapshot (time-windowed analysis), then applies `min-mean-time-ms` / `min-total-time-ms` thresholds | `pg_stat_statements` |
| **Resolve params** | `ParamResolver` | Replaces `$N` placeholders with real values sampled from the planner's statistics so the query becomes runnable. `LIMIT`/`OFFSET` params come from config (`bench-limit`, `bench-offset`) | `pg_stats` (MCVs, histograms), `information_schema` |
| **DIAGNOSE (plan)** | `ExplainTool` | Gets the real execution plan. Runnable SELECT → `EXPLAIN (ANALYZE, BUFFERS)` with runtime stats. Unresolvable params → `EXPLAIN (GENERIC_PLAN)`, structure only. Runs inside a rolled-back transaction | `EXPLAIN` family |
| **DIAGNOSE (facts)** | `TableInspectionTool` | Table size, estimated rows, every index definition, per-column stats, last ANALYZE time | `pg_class`, `pg_indexes`, `pg_stats`, `pg_stat_user_tables` |
| **CLASSIFY + HYPOTHESIZE** | `AnthropicLlmClient` + `Prompts` | The only LLM call per query: reads all evidence above *plus the other frequent queries in the window* (for N+1 parent/child correlation), returns JSON — classification, pathology, **confidence** (HIGH/MEDIUM/LOW), root cause, one proposed fix, tradeoffs | — (LLM reasons, never measures) |
| **CROSS-CHECK** | `AgentLoop` | Deterministically verifies the LLM's claimed signal exists in the data (cast in the plan for IMPLICIT_CAST, high rows/call for UNBOUNDED_RESULT, calls ≥ 100 for N+1…). On mismatch, downgrades the finding to LOW confidence and annotates it — never reclassifies | — |
| **TEST** (Phase 2) | `HypoPGTool` | Creates the proposed index *virtually* (no disk, no lock, session-scoped), re-plans the query, compares estimated cost before/after | `hypopg` extension |
| **EVALUATE** | `AgentLoop` | If the estimated speedup is below `min-estimated-speedup`, sends the failed result back to the LLM: propose a *different* fix or concede with `{"discard": true}`. Capped at `max-retries-per-query`. This feedback edge is what makes the system an agent rather than a pipeline | — |
| **VERIFY** (Phase 3, opt-in) | `BenchmarkTool` + `ApplyTool` | Times the query N runs (warm-up first), really creates the index, times again → measured before/after delta. Only runs when `apply-fixes=true` AND params were resolved | `EXPLAIN ANALYZE` timing, DDL |
| **EVALUATE** (Phase 3) | `AgentLoop` + `ApplyTool` | If the *measured* speedup is below `min-measured-speedup`, the index is dropped again and the finding stays `verified=false` — a regression is never reported as a win | DDL (`DROP INDEX`) |
| **REPORT** | `AgentRunner`, `EmailReporter` | Groups findings into "fix in the database" / "fix in your code" / "fix in both layers"; prints to log and sends the HTML email report | — |

### Why ranking by total time matters

A 0.3 ms query called 500,000 times costs more than a 2-second query called 60 times.
Tools that rank by mean execution time are structurally blind to N+1 storms — the single
most common ORM pathology. `SlowQueryTool` ranks by `total_exec_time` and surfaces
`calls`, which is exactly the signal the classifier needs.

### Why parameter resolution matters

`pg_stat_statements` stores queries normalized: `WHERE status = 'pending'` becomes
`WHERE status = $1`. A query with `$1` in it cannot be executed. Substituting `NULL`
(the naive fix) produces garbage: `WHERE status = NULL` matches nothing, plans
short-circuit, and any "measured" number describes a query nobody runs.

`ParamResolver` instead samples a *real* value from `pg_stats` — the most common value
for that column, or the histogram midpoint — so the re-run query touches representative
data. When it can't resolve a parameter, the agent degrades honestly: plan structure via
`GENERIC_PLAN`, and no benchmarking at all (refusing a measurement beats faking one).

Limitation to know about: a sampled value is representative, not the actual production
value. For example, `email LIKE $1` will be re-run with a plain email, not the
`'%@example.com'` pattern production used — the leading-wildcard signal lives only in the
`pg_stat_statements` numbers in that case, and the prompt tells the LLM exactly that.
Recovering true runtime parameters would require `auto_explain` log sampling (future work).

## 3. The Postgres features — what they are and what they give you

| Feature | What it is | What you get from it |
|---|---|---|
| **`pg_stat_statements`** | Core contrib extension (must be in `shared_preload_libraries`). Records every statement, normalized, with cumulative `calls`, `total_exec_time`, `mean_exec_time`, `rows` | The "what is actually expensive" list. Crucially it's the only place the *frequency* dimension exists — your APM shows a 0.3 ms query as healthy; this shows it ran 500k times |
| **`EXPLAIN (ANALYZE, BUFFERS)`** | Executes the query and reports the real plan: Seq Scan vs Index Scan, actual vs estimated row counts, buffer hits vs disk reads, per-node timing | The "why is it expensive" answer. Seeing `Filter: ((id)::text = '10%')` with a Seq Scan tells you instantly that Java bound a String against a bigint column. **Warning: ANALYZE executes the statement** — that's why `ExplainTool` is SELECT-only and wraps in a rolled-back transaction |
| **`EXPLAIN (GENERIC_PLAN)`** (PG16+) | Plans a parameterized query without executing it | Lets the agent see plan structure for queries whose parameters it couldn't reconstruct — safely, with zero execution |
| **`pg_stats`** | View over the planner's per-column statistics: most common values, frequency, histograms, null fraction, n_distinct | Two uses: evidence for the LLM (data skew explains planner choices), and the source of realistic sample values for `ParamResolver` |
| **`pg_class` / `pg_indexes` / `pg_stat_user_tables`** | System catalogs: relation sizes, row estimates, full index DDL, last (auto)ANALYZE time | Proof that "an index exists but isn't used" — that's what separates `IMPLICIT_CAST` / `LEADING_WILDCARD` from plain `MISSING_INDEX`, and stale-ANALYZE detection for `STALE_STATS` |
| **HypoPG** | Third-party extension: hypothetical, in-memory indexes the planner sees but that don't exist on disk | Answer "would this index help?" in milliseconds with zero risk: no 10-minute build, no write lock, no disk, vanishes at session end. Same technique Dexter and pganalyze use. The demo image installs it (`Dockerfile`) |
| **`statement_timeout`** | Session/statement-level kill switch | Guarantees the agent can never hang the database with a runaway EXPLAIN ANALYZE. Set on every connection (Hikari init SQL) and per-tool from `pgagent.loop.statement-timeout-ms` |

## 4. The pathologies and their database-side fingerprints

The tools surface raw signals; the LLM does the classification. The detection signatures:

| Pathology | Fingerprint in the telemetry | Fix layer |
|---|---|---|
| `N_PLUS_ONE` | Huge `calls`, tiny `mean`, top-ranked `total`, `rows/call ≈ 1`, single-row FK-lookup shape | App: `JOIN FETCH`, `@EntityGraph`, `@BatchSize` |
| `IMPLICIT_CAST` | Plan filter casts an indexed column (`(id)::text = $1`) + Seq Scan despite a usable index in `pg_indexes` | App: bind the correct Java type |
| `DEEP_OFFSET` | `LIMIT $1 OFFSET $2` in text + rows processed ≫ rows returned | App: keyset pagination (+ supporting index → MIXED) |
| `UNBOUNDED_RESULT` | High `rows/call`, no `LIMIT` in query text | App: pagination or projection |
| `LEADING_WILDCARD` | `LIKE` with leading `%` + Seq Scan + unusable btree present | MIXED: `pg_trgm` GIN index, or app-side search path |
| `MISSING_INDEX` | Seq Scan on large table with a sargable filter, no index covers it | DB: `CREATE INDEX` (HypoPG-testable) |
| `STALE_STATS` | Plan estimate vs actual rows off by ≥10x, old `last_analyze` | DB: `ANALYZE` |

Supporting signal: Hibernate-generated SQL is recognizable (aliases like `o1_0`, fully
enumerated columns). It justifies *suspecting* the app layer; it is never treated as proof.

### Reliability — correlation, confidence, and a cross-check

Three things keep the application-layer classification trustworthy rather than a one-shot guess:

- **Parent/child correlation.** The perceive step hands the classifier the other frequent queries
  in the window, so an N+1 is confirmed by finding the *parent* that drives it (a child lookup
  whose `calls` ≈ parent calls × rows-per-parent), not by one query's stats in isolation. With a
  matching parent → HIGH confidence; without one → only "probably N+1", marked lower.
- **A confidence level on every finding** (`HIGH` / `MEDIUM` / `LOW`). HIGH when independent signals
  agree; LOW when the call rests on a *sampled* parameter value, which is the honest state for
  `LEADING_WILDCARD` and `IMPLICIT_CAST` — `pg_stat_statements` normalizes the literal away, so the
  plan was run with a value sampled from `pg_stats` that may differ from production.
- **A deterministic cross-check.** After the LLM classifies, plain Java verifies the claimed signal
  is actually present (a cast in the plan for `IMPLICIT_CAST`, high rows/call for `UNBOUNDED_RESULT`,
  calls ≥ 100 for `N_PLUS_ONE`…). On a mismatch the finding is downgraded to LOW and annotated —
  catching the occasional LLM overreach without overriding its judgment.

### Honesty rule for APP_PROBLEM findings

Application-layer fixes can't be benchmarked by this agent (the fix lives in code it can't run), so
those findings are always `verified = false` with the note "not applicable — fix is
application-side". The agent never fabricates an expected improvement for them. It may state the
*arithmetic* implication of measured numbers (e.g. "eliminating the per-row lookup removes ~500k
calls/day of measured 0.3 ms each"), clearly labelled as derived from measurement, not predicted.
The only verification it can offer for a code fix is the cross-run "Resolved" signal: the query
storm disappearing from a later run's report after you ship the change.

## 5. Configuration reference (`pgagent.*` in `application.yml`)

| Property | Default | Meaning |
|---|---|---|
| `anthropic.api-key` | env `ANTHROPIC_API_KEY` | LLM credentials — required |
| `anthropic.model` | `claude-sonnet-4-6` | Model for classification calls |
| `loop.max-iterations` | 10 | Hard stop — the agent always halts |
| `loop.slow-query-limit` | 10 | Candidates fetched from `pg_stat_statements` |
| `loop.min-mean-time-ms` / `min-total-time-ms` | 50 / 500 | A query qualifies if it exceeds either threshold |
| `loop.statement-timeout-ms` | 5000 | Applied to every diagnostic statement |
| `loop.apply-fixes` | **false** | Phase 3 master switch. Enabling it also requires `spring.datasource.hikari.read-only: false` |
| `loop.benchmark-runs` | 5 | Timed runs per benchmark (plus one warm-up) |
| `loop.bench-limit` / `bench-offset` | 50 / 100000 | Literals substituted for `LIMIT $n` / `OFFSET $n` when re-running queries |
| `loop.min-estimated-speedup` | 1.5 | HypoPG estimate below this → hypothesis fails, LLM asked to revise or discard |
| `loop.min-measured-speedup` | 1.1 | Measured speedup below this → applied index is dropped, finding stays unverified |
| `loop.max-retries-per-query` | 2 | Revision rounds per query before the agent reports the weak result as-is |
| `report.recipient-email` | env `REPORT_RECIPIENT_EMAIL` | If set (and mail configured), sends the HTML report |
| `privacy.share-data-values` | **false** | Opt-in: include real cell values (MCVs, sampled literals) in LLM evidence — better skew reasoning + partial-index DDL, at the cost of data leaving your network |
| `snapshot.enabled` | false | Time-windowed analysis: diff each run against the previous run's saved counters, so numbers mean "since last run" instead of "since stats reset" |
| `snapshot.path` | `pgagent-snapshot.json` | Local JSON file holding the previous run's counters — client-side so the agent stays read-only |

## 6. Safety model

Layered, enforced in code rather than documentation:

1. **Read-only by default** — `hikari.read-only: true` and `apply-fixes: false`. Phase 3
   requires flipping both, deliberately.
2. **SELECT-only execution** — `ExplainTool` refuses `EXPLAIN ANALYZE` on non-SELECTs
   (ANALYZE executes the statement; on a write it would perform the write), rejects
   multi-statement input, and runs inside a transaction that is always rolled back.
   `BenchmarkTool` is SELECT-only and refuses unresolved-parameter queries.
3. **`ApplyTool` allowlist** — executes only `CREATE [UNIQUE] INDEX` statements, and only
   when `apply-fixes=true`. Builds always run with `CONCURRENTLY` (no write-blocking lock
   on the table), a failed concurrent build's leftover INVALID index is cleaned up, and an
   index whose measured improvement fails the bar is dropped again.
4. **`statement_timeout` everywhere** — no diagnostic query can run away.
5. **Honest degradation** — unresolved params → `GENERIC_PLAN` + no benchmark; unparseable
   LLM JSON → finding discarded, never a crash or a guess.

Where to point it: a restored clone or thin clone (best), a read replica (fine for
Phases 1–2; note you cannot create indexes on a replica, so Phase 3 needs a clone),
never the production primary.

Data privacy defaults to redaction: real cell values go to the LLM only when
`pgagent.privacy.share-data-values: true` is set. By default, `pg_stats` most-common values
are excluded from the evidence payload and sampled string literals are scrubbed back to `$N`
placeholders in plan text. Enabling sharing improves data-skew reasoning and unlocks
value-specific partial-index recommendations — see "Security & data privacy" in the README
for the full what-goes/what-it-buys breakdown.

## 7. Build phases

| Phase | What works | Writes to the DB? |
|---|---|---|
| 1 | Perceive → diagnose → classify → report. APP_PROBLEM detection is fully functional here | No |
| 2 | HypoPG estimated before/after for DB fixes | No (hypothetical indexes are in-memory) |
| 3 | Real benchmark + opt-in apply path | Yes — `CREATE INDEX` only, double opt-in |

## 8. Running it

```bash
docker compose up -d --build              # demo DB: postgres:16 + pg_stat_statements + HypoPG
cp .env.example .env                      # add ANTHROPIC_API_KEY (and mail creds if wanted)
set -a && source .env && set +a
mvn spring-boot:run                       # one full agent run, report at the end
mvn test                                  # unit tests + Testcontainers integration tests
docker compose down -v                    # full reset (wipes stats; workload re-runs on next up)
```

The demo database plants five problems (one per pathology) at startup via
`db/init/02-workload.sql`. A correct run classifies four of them as application-layer —
which is the point: a traditional index advisor would have proposed five indexes and
fixed exactly one problem.

## 9. How you benefit as a developer

- **You see the queries your APM hides.** Per-request tracing shows each N+1 call as a
  healthy sub-millisecond query; only the database-side `calls × mean` view exposes the storm.
- **You get the fix at the right layer.** When the root cause is ORM misuse, an index is a
  band-aid that costs write performance and hides the bug. The agent says "change
  `FetchType` / use keyset pagination" instead — with the plan fragment and numbers to
  paste straight into a PR description.
- **DB fixes arrive pre-validated.** HypoPG answers "would this index help?" before any
  index is built; Phase 3 gives a measured before/after, not a promise.
- **Every claim is auditable.** Each finding carries the raw evidence (stats, plan
  fragments, index list). Nothing in the report is an LLM estimate, so you can defend it
  to your team — or refute it, which is just as valuable.
