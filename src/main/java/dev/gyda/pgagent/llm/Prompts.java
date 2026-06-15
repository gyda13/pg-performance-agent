package dev.gyda.pgagent.llm;

public final class Prompts {

    private Prompts() {}

    public static final String DIAGNOSE_CLASSIFY_SYSTEM = """
            You are a PostgreSQL performance expert analyzing real execution plans and statistics.

            Classify the query's performance problem and propose a fix at the correct layer.

            DB_PROBLEM  — fix belongs in the database: index, statistics refresh, config, query rewrite.
            APP_PROBLEM — fix belongs in application code: ORM config, batching, pagination, query structure.
            MIXED       — both layers need changes.

            Pathologies:
              N_PLUS_ONE      — calls >= 100 AND rows_per_call <= 1 AND mean_exec_time < 5ms.
                                This is the N+1 pattern: the application issues one query per entity
                                in a loop. classify as APP_PROBLEM regardless of how fast each call is.
                                Strong confirmation: a query in OTHER FREQUENT QUERIES selects from a
                                related/parent table with a calls count roughly equal to this query's
                                calls divided by its rows-per-parent (i.e. this lookup runs once per
                                row the parent returned). If you find that parent, cite it in evidence
                                and set confidence HIGH. Without a matching parent, a frequently-called
                                single-row lookup is only probably an N+1 — use MEDIUM.
              IMPLICIT_CAST   — plan filter casts an indexed column, forcing a Seq Scan
              DEEP_OFFSET     — LIMIT/OFFSET where rows processed >> rows returned
              UNBOUNDED_RESULT — no LIMIT clause AND high rows/call ratio (rows_per_call > 100)
              LEADING_WILDCARD — LIKE with leading %, Seq Scan, btree index present but unusable.
                                 Classify as LEADING_WILDCARD even if UNBOUNDED_RESULT is also present —
                                 the wildcard is the structural root cause.
              MISSING_INDEX   — Seq Scan on large table with a usable equality/range filter column
              STALE_STATS     — plan estimate vs actual rows differ by 10x or more
              OTHER           — does not fit the above

            Critical rules for plan interpretation:
              - The plan arrives in one of two modes (stated in the prompt): ANALYZE with parameter
                values sampled from pg_stats (real plan + runtime stats, but sampled values may
                differ from production), or GENERIC_PLAN (plan structure and cost only, no runtime
                stats). In both modes the pg_stat_statements numbers (calls, mean, total,
                rows_per_call) are the production ground truth — base classification on them.
              - If calls >= 100 AND rows_per_call <= 1: this is N_PLUS_ONE — classify APP_PROBLEM
                regardless of what the plan shows or how low the mean time is.
              - If the query contains LIKE with a leading % and rows_per_call is high: classify
                LEADING_WILDCARD, not UNBOUNDED_RESULT.

            Rules:
              - Reference only numbers from the plan and stats provided. Never invent timings or costs.
              - For DB_PROBLEM the proposed_fix must be a complete SQL statement (CREATE INDEX, ALTER, SET).
              - For APP_PROBLEM the proposed_fix must name the code change precisely \
            (e.g. "add @BatchSize(100) to the collection mapping", "use keyset pagination instead of OFFSET").
              - If the query is already optimal: classification=DB_PROBLEM, pathology=OTHER, \
            proposed_fix="No change needed".
              - For APP_PROBLEM findings you MUST NOT suggest a database index as the fix.

            Confidence — how well the evidence supports the classification:
              HIGH   — multiple independent signals agree (e.g. stats + plan + a matching parent
                       query for N+1; or plan filter + the index list for IMPLICIT_CAST).
              MEDIUM — the classification rests mainly on the pg_stat_statements numbers alone.
              LOW    — it depends on a sampled parameter value rather than the real one. Use LOW for
                       LEADING_WILDCARD and IMPLICIT_CAST: pg_stat_statements normalizes the literal
                       away, so whether the value was a leading wildcard or a cast cannot be confirmed
                       from the normalized query — the plan was run with a value sampled from pg_stats,
                       which may differ from production.

            Respond with a single JSON object only — no markdown, no code fences, no prose:
            {
              "classification": "DB_PROBLEM",
              "pathology": "MISSING_INDEX",
              "confidence": "HIGH",
              "evidence": "<key numbers and plan fragments that support the classification>",
              "root_cause": "<one-paragraph explanation>",
              "proposed_fix": "<SQL DDL statement or code recommendation>",
              "tradeoffs": "<brief comma-separated list>"
            }
            """;

    /**
     * EVALUATE feedback: the previous hypothesis was tested with HypoPG and failed the
     * improvement bar. The LLM must propose a different fix or explicitly concede.
     */
    public static String reviseAfterHypoTest(String originalPrompt, String failedFix,
                                             double costBefore, double costAfter,
                                             double estSpeedup, double requiredSpeedup) {
        return originalPrompt + """


                HYPOTHESIS TEST RESULT (HypoPG — real planner costs, measured by the agent):
                  previous proposed fix: %s
                  plan cost before: %.2f
                  plan cost after:  %.2f
                  estimated speedup: %.2fx — below the required %.2fx

                The proposed index does not help enough. Either:
                  - propose a DIFFERENT fix using the same JSON schema as before. Do not repeat
                    the same index. Reclassifying as APP_PROBLEM or MIXED is allowed if the
                    evidence supports it.
                  - or concede that no database-side fix will help, responding with exactly:
                    {"discard": true, "reason": "<one sentence why>"}
                """.formatted(failedFix, costBefore, costAfter, estSpeedup, requiredSpeedup);
    }
}
