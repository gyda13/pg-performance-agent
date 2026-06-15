# CLAUDE.md — Postgres Performance Agent

Guidance for Claude Code when working in this repository.

## What this project is

A Spring Boot agent that connects to a real Postgres database, finds the most expensive queries,
diagnoses why they're expensive from real execution plans and real statistics, **classifies each
finding as a database problem or an application/ORM problem**, proposes a fix at the correct
layer, and (Phases 2–3) verifies database-side fixes with estimated and then measured
before/after numbers.

The differentiator vs. existing index advisors (Dexter, pganalyze): this agent detects
**ORM-misuse pathologies from database telemetry alone** — N+1 storms, implicit type casts,
deep OFFSET pagination, unbounded result sets, leading-wildcard searches — and recommends
application-layer fixes for them instead of pretending an index will help.

## The two non-negotiable rules

1. **The LLM never produces a number.** Every metric (execution time, call counts, rows, plan
   cost, deltas) comes from real query execution or `pg_stat_statements`. The LLM only reasons
   over evidence: reads plans, classifies, hypothesizes, decides the next action. Deterministic
   Java code does all measurement. Never write code that asks the LLM to estimate, predict, or
   fill in a metric.
2. **APP_PROBLEM findings are never "verified".** Application-layer fixes (code changes) cannot
   be benchmarked by this agent. Such findings must carry `verified = false` and a verification
   note of "not applicable — fix is application-side". Never fabricate an expected improvement
   for them. Stating arithmetic implications of *measured* numbers is fine if labeled as such.
## Architecture and flow

```
PERCEIVE   SlowQueryTool reads pg_stat_statements
           — ranks by total_exec_time AND calls, never mean time alone
           — returns: normalized query, calls, mean_exec_time, total_exec_time, rows/call
DIAGNOSE   ExplainTool runs EXPLAIN (ANALYZE, BUFFERS) on the top candidate
           TableInspectionTool gathers table size, indexes, column stats
CLASSIFY   LLM assigns Classification: DB_PROBLEM | APP_PROBLEM | MIXED
           and Pathology: N_PLUS_ONE | IMPLICIT_CAST | DEEP_OFFSET | UNBOUNDED_RESULT |
           LEADING_WILDCARD | MISSING_INDEX | STALE_STATS | OTHER
HYPOTHESIZE DB_PROBLEM  -> exactly one concrete DDL/config fix per iteration
            APP_PROBLEM -> named pathology + code-level recommendation + evidence chain
TEST       (Phase 2, DB fixes only) HypoPG hypothetical index -> re-EXPLAIN -> estimated delta
VERIFY     (Phase 3, DB fixes only) apply, benchmark, measured before/after delta
EVALUATE   keep / discard / try another hypothesis (max retries per query)
LOOP       next query until termination; always log WHY the loop stopped
```

Termination conditions (all must be implemented — the agent must always halt):
nothing above the cost threshold, max iterations reached, or marginal recent gains.

## Pathology detection signatures (implement detection hints in code, reasoning in LLM)

The perceive/diagnose tools should surface the raw signals; the LLM does the classification.
Do NOT hard-code the classification in Java — but DO make sure the tools expose the signals
that make classification possible:

- **N_PLUS_ONE:** huge `calls`, tiny `mean_exec_time`, top-ranked `total_exec_time`, single-row
  FK lookup shape. Requires perceive to return `calls` and rank by total time — a mean-time-only
  ranking is structurally blind to this and is considered a bug.
- **IMPLICIT_CAST:** plan filter contains a cast on an indexed column, e.g. `((id)::text = $1)`,
  with a Seq Scan despite a usable index. Requires ExplainTool output to preserve filter
  expressions verbatim and TableInspectionTool to list existing indexes.
- **DEEP_OFFSET:** `LIMIT $1 OFFSET $2` in query text + plan rows-processed >> rows-returned.
- **UNBOUNDED_RESULT:** high `rows / calls` ratio, no LIMIT in query text.
- **LEADING_WILDCARD:** `LIKE` with leading `%` + Seq Scan + btree index present but unusable.
- Hibernate-generated SQL is recognizable (aliases like `o1_0`, fully enumerated columns).
  This is a supporting signal for APP_PROBLEM, not proof — findings should phrase it as such.
## Tech stack and conventions

- Java 21, Spring Boot 3.x, Maven. Records for all model types (`SlowQuery`, `Finding`,
  `Classification`, `Pathology`). Constructor injection only, no field `@Autowired`.
- Plain `JdbcTemplate` for all database access in tools — no JPA in this project (ironic given
  what it diagnoses, and deliberate: the agent's own queries must be transparent).
- Config via typed `AgentProperties` (`pgagent.*` prefix). No magic strings for thresholds.
- LLM calls go only through `LlmClient`. `AnthropicLlmClient` is the implementation
  (Messages API, text in / text out). Keep prompts in one place (a `prompts/` resource folder
  or constants class) — never scattered inline through the loop.
- For structured LLM output (classification, hypothesis), instruct the model to return JSON
  only, parse defensively, and treat parse failure as "hypothesis discarded", never as a crash.
## Safety rules (enforced in code, not just documented)

- Read-only by default. The datasource user in `application.yml` must not have DDL/DML rights
  until Phase 3, and Phase 3 apply-paths must be explicit opt-in via config flag.
- `ExplainTool` must refuse to EXPLAIN ANALYZE anything that is not a single SELECT statement
  (EXPLAIN ANALYZE executes the statement — on a write, it performs the write). Wrap in a
  transaction and ROLLBACK as a second layer of defense.
- Every connection sets `statement_timeout` (from `AgentProperties`).
- Never log or echo credentials. `.env` is gitignored; keep it that way.
- Never point tests at anything but the docker-compose demo database.
## Repository layout

```
src/main/java/dev/gyda/pgagent/
├── PgAgentApplication.java       entry point
├── AgentRunner.java              CommandLineRunner — starts the loop
├── agent/AgentLoop.java          orchestrator (main build target)
├── tools/
│   ├── SlowQueryTool.java        WORKING reference — copy its pattern for other tools
│   ├── ExplainTool.java          stub
│   ├── TableInspectionTool.java  stub
│   └── CodeInspectionTool.java   Phase 4, optional — repo grep to CONFIRM a suspected
│                                 pathology (e.g. find the FetchType.LAZY behind an N+1);
│                                 confirmatory only, DB-only diagnosis must stand alone
├── llm/                          LlmClient interface + AnthropicLlmClient
├── model/                        SlowQuery, Finding, Classification, Pathology
└── config/AgentProperties.java
 
db/init/01-init.sql               demo schema with planted problems
db/init/02-workload.sql           deliberately-bad workload: N+1 storm, deep OFFSET,
                                  mistyped-parameter query, unbounded select
docker-compose.yml                demo Postgres (image must include pg_stat_statements + HypoPG)
```

## Build phasing — do not skip ahead

1. **Phase 1:** perceive → explain → classify → diagnose → report. APP_PROBLEM detection is
   fully functional in this phase (it needs no writes). Finish and demo this before Phase 2.
2. **Phase 2:** HypoPG estimated before/after for DB_PROBLEM findings.
3. **Phase 3:** real benchmark + guarded opt-in apply path.
4. **Phase 4 (optional):** CodeInspectionTool.
## Common commands

```bash
docker compose up -d                      # start demo DB
docker compose down -v                    # reset demo DB (wipes stats — rerun workload after)
set -a && source .env && set +a           # load env
mvn spring-boot:run                       # run the agent
mvn test                                  # unit tests (Testcontainers for tool tests)
mvn -q -DskipTests package                # build jar
```

## Testing expectations

- Tool classes get integration tests against Testcontainers Postgres (same image as compose,
  so pg_stat_statements/HypoPG are present).
- `AgentLoop` logic gets unit tests with a fake `LlmClient` returning canned classifications —
  never call the real API in tests.
- Each pathology in `02-workload.sql` should have a test asserting the perceive+diagnose output
  contains the signals needed to classify it (calls count for N+1, cast in filter for
  IMPLICIT_CAST, etc.). This is the project's core promise; protect it with tests.
## Findings report format

Each iteration emits a `Finding`: query, classification, pathology, evidence (the actual
numbers and plan fragments), root_cause (LLM reasoning, plain language), fix (DDL or code
recommendation), verified (boolean — see rule 2), delta (measured only, Phase 3).
End-of-run report groups findings by classification: "fix in the database" vs "fix in your code",
each with its evidence chain.