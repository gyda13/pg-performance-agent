# Postgres Performance Agent

An agent that connects to a real Postgres database, finds the queries costing you the most, and
works out **why** from real execution plans and statistics. It classifies each problem as a
**database** issue or an **application/ORM** issue, proposes a fix at the right layer, and verifies
database-side fixes by measuring before/after. One principle holds throughout: **the LLM never
produces a number** — every metric comes from real query execution; the model only reasons over
that evidence.

A lot of slow Postgres — especially under Spring Boot / Hibernate — isn't a missing index. It's the
application using the database badly: N+1 query storms, deep `OFFSET` pagination, implicit type
casts that disable indexes, unbounded result sets. These leave recognizable fingerprints in
`pg_stat_statements` and in execution plans, so the agent diagnoses them from the database's
telemetry alone — without access to your code.

| Classification | Meaning | Fix layer | Verified by the agent? |
|---|---|---|---|
| `DB_PROBLEM` | The query is reasonable; the database is under-equipped | DDL / config | Yes — HypoPG + benchmark |
| `APP_PROBLEM` | The database did what it was asked; the app asked badly (ORM misuse) | Java / JPA code | No — reported with evidence |
| `MIXED` | Both (e.g. an N+1 that's also missing an FK index) | Both | Partially |

So when the right answer is a code change, the agent says so and points at it, instead of proposing
an index that wouldn't help.

## Security & data privacy

The agent runs **entirely inside your infrastructure**. It connects to your Postgres directly, and
all measurement (`EXPLAIN ANALYZE`, HypoPG, benchmarks) happens over that local connection. The only
thing sent to the Anthropic API is diagnostic evidence for classification: normalized SQL
(`WHERE status = $1`), schema and index names, table sizes, statistics counters, and a value-scrubbed
execution plan.

**Real data never leaves by default.** `pg_stats` most-common values (real emails, names) and sampled
literals are stripped before anything is sent; credentials live in `.env` (gitignored) and are never
logged or sent. An opt-in flag (`pgagent.privacy.share-data-values`) can include data values for
sharper skew reasoning — leave it off for production data. Schema and query *shapes* do go to the API
(inherent to having an LLM read plans); if even that is sensitive, plug a self-hosted model into the
`LlmClient` interface.

**Safety:** point it at a replica or restored clone, never the primary; it is read-only by default
(writes happen only in the opt-in Phase 3); a `statement_timeout` caps every query. Full breakdown in
[docs/DEV_GUIDE.md](docs/DEV_GUIDE.md).

## Similar tools

| Tool | What it does | Relationship to this project |
|---|---|---|
| **Dexter / pganalyze Index Advisor** | Suggest indexes via hypothetical (HypoPG) indexes | Same HypoPG technique for the DB-side check; here it's one step in the loop, not the whole tool |
| **pganalyze / pgMustard** | Continuous monitoring and plan visualization | Strong at watching a database over time; they don't classify DB-vs-app or apply-and-measure fixes |
| **DBtune / OtterTune** | Tune server parameters (`shared_buffers`, etc.) | Different layer — server config, not individual queries or app behavior |
| **Hypersistence Optimizer, Digma, APM N+1 detectors** | Catch ORM misuse from inside the running app | Same pathologies, opposite direction — they instrument the application; this reads the database telemetry |
| **Generic "AI DBA" chat tools** | Give tuning advice from a prompt | Don't connect to real plans or measure anything |

Where this project sits: it classifies whether the root cause is the database or the application,
diagnoses ORM misuse from database telemetry alone, and verifies database-side fixes by measuring
them.

## Phases

The agent works in phases. Phase 1 always runs; the rest are optional and turned on by config.

1. **Phase 1 — diagnosis + classification (always on):** find slow queries → EXPLAIN → classify →
   report. Read-only and safe; application-layer detection works fully here with no write access.
2. **Phase 2 — estimated before/after (optional):** HypoPG hypothetical-index testing for database
   fixes. Active when the database has the HypoPG extension; still read-only.
3. **Phase 3 — apply and measure (optional, opt-in):** real benchmarking and a guarded "apply the
   change" path for database fixes. Off by default — requires setting `pgagent.loop.apply-fixes` and
   `spring.datasource.hikari.read-only: false`. Point it at a clone, not production.

For application-layer findings the agent stops at a precise, evidence-backed recommendation (named
pathology + code-level fix + the numbers); applying it in your codebase is left to you, so the agent
never needs access to your source.

## Two ways to run it

Same tools, same safety rules — the difference is *who decides the steps*.

| Mode | Who drives | Cost / speed | When to use |
|---|---|---|---|
| **Pipeline** (default) | Fixed Java sequence: perceive → diagnose → classify → test → verify | ~1 LLM call per query, fast, fully predictable | Routine runs, reports you need to reproduce, tight budgets |
| **Autonomous** | The LLM decides which tools to call, in what order, and when to stop | Many LLM calls (10–20×), slower, path varies per run | Open-ended investigation, cross-query correlation, novel cases |

The **default is Pipeline** — cheap, predictable, and the right choice for most runs. Switch to
autonomous with one config flag:

```yaml
pgagent:
  loop:
    autonomous: true   # default false → deterministic pipeline
```

Either way Java still does the perceive step (the entry point) and **every measurement** — the LLM
never produces a number.

## Database writes always require your approval

In **both** modes, anything that writes to the database in Phase 3 — `CREATE INDEX`, `DROP INDEX`,
`ANALYZE` — is shown to you verbatim, with HypoPG's *estimated* improvement as a preview, and runs
only after you type `y`:

```
──────────── DATABASE WRITE — APPROVAL REQUIRED ────────────
The agent wants to run this index against the database:
    CREATE INDEX ON orders (created_at)
Preview — HypoPG estimates 25.7x faster (estimated only; nothing applied yet).
Execute this statement now? [y/N]
```

Read-only diagnostics (`EXPLAIN`, table inspection, HypoPG estimates) never prompt. If no console is
attached, the answer defaults to "no" — the database is left unchanged rather than written
unattended. (Phase 3 itself still requires `pgagent.loop.apply-fixes: true`.)

## Using it

**Why:** it surfaces the queries actually costing you the most — ranked by total time, so it catches
N+1 storms that per-request dashboards miss — tells you whether to fix the database or the code, and
proves database fixes with real measurements. It's especially useful for Spring Boot / Hibernate
teams without a dedicated DBA.

**How** (needs Java 21, Maven 3.9+, Docker):

```bash
docker compose up -d --build      # demo Postgres with pg_stat_statements + HypoPG
cp .env.example .env              # add your ANTHROPIC_API_KEY
set -a && source .env && set +a
mvn spring-boot:run               # one run, grouped report at the end
```

Point it at your own database by editing the datasource in `.env`. Reset the demo with
`docker compose down -v && docker compose up -d --build`.

## Learn more

- **[docs/EXAMPLE.md](docs/EXAMPLE.md)** — a tested run against the demo database: the planted
  problems and exactly what the agent reported.
- **[docs/DEV_GUIDE.md](docs/DEV_GUIDE.md)** — how the agent works step by step, the Postgres
  features it uses, the full config reference, and the reliability model.

## License

MIT — see `LICENSE`.
