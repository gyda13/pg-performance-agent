package dev.gyda.pgagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gyda.pgagent.config.AgentProperties;
import dev.gyda.pgagent.llm.LlmClient;
import dev.gyda.pgagent.llm.Prompts;
import dev.gyda.pgagent.model.AgentRunResult;
import dev.gyda.pgagent.model.BenchmarkResult;
import dev.gyda.pgagent.model.Classification;
import dev.gyda.pgagent.model.ColumnStat;
import dev.gyda.pgagent.model.Confidence;
import dev.gyda.pgagent.model.Finding;
import dev.gyda.pgagent.model.HypoResult;
import dev.gyda.pgagent.model.Pathology;
import dev.gyda.pgagent.model.PerceptionResult;
import dev.gyda.pgagent.model.SlowQuery;
import dev.gyda.pgagent.model.TableStats;
import dev.gyda.pgagent.tools.ApplyTool;
import dev.gyda.pgagent.tools.BenchmarkTool;
import dev.gyda.pgagent.tools.ExplainTool;
import dev.gyda.pgagent.tools.HypoPGTool;
import dev.gyda.pgagent.tools.ParamResolver;
import dev.gyda.pgagent.tools.SlowQueryTool;
import dev.gyda.pgagent.tools.TableInspectionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final SlowQueryTool slowQueryTool;
    private final ExplainTool explainTool;
    private final TableInspectionTool tableInspectionTool;
    private final ParamResolver paramResolver;
    private final HypoPGTool hypoPGTool;
    private final BenchmarkTool benchmarkTool;
    private final ApplyTool applyTool;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final AgentProperties props;

    public AgentLoop(SlowQueryTool slowQueryTool,
                     ExplainTool explainTool,
                     TableInspectionTool tableInspectionTool,
                     ParamResolver paramResolver,
                     HypoPGTool hypoPGTool,
                     BenchmarkTool benchmarkTool,
                     ApplyTool applyTool,
                     LlmClient llm,
                     ObjectMapper mapper,
                     AgentProperties props) {
        this.slowQueryTool = slowQueryTool;
        this.explainTool = explainTool;
        this.tableInspectionTool = tableInspectionTool;
        this.paramResolver = paramResolver;
        this.hypoPGTool = hypoPGTool;
        this.benchmarkTool = benchmarkTool;
        this.applyTool = applyTool;
        this.llm = llm;
        this.mapper = mapper;
        this.props = props;
    }

    public AgentRunResult run() {
        List<Finding> findings = new ArrayList<>();

        PerceptionResult perception = slowQueryTool.getSlowQueries();
        List<SlowQuery> candidates = perception.candidates();
        log.info("Found {} candidate slow queries (mean >= {} ms OR total >= {} ms).",
                candidates.size(),
                props.getLoop().getMinMeanTimeMs(),
                props.getLoop().getMinTotalTimeMs());

        if (candidates.isEmpty()) {
            log.info("No slow queries found. Populate pg_stat_statements by running the workload first.");
            return new AgentRunResult(findings, perception.trends(),
                    perception.resolvedQueries(), perception.windowStart());
        }

        int maxIter = props.getLoop().getMaxIterations();
        int iteration = 0;

        for (SlowQuery q : candidates) {
            if (iteration >= maxIter) {
                log.info("Stopping: max-iterations ({}) reached.", maxIter);
                break;
            }
            iteration++;

            log.info("[{}/{}] DIAGNOSE: mean={} ms  total={} ms  calls={}  {}",
                    iteration, Math.min(candidates.size(), maxIter),
                    fmt(q.meanTimeMs()), fmt(q.totalTimeMs()), q.calls(), abbreviate(q.queryText()));

            // --- DIAGNOSE ---
            // Resolve $N to literals sampled from pg_stats so EXPLAIN ANALYZE and benchmarks
            // measure the real query shape. Unresolvable -> GENERIC_PLAN, no Phase 3.
            Optional<ParamResolver.Resolution> resolved = paramResolver.resolve(q.queryText());
            String runnableSql = resolved.map(ParamResolver.Resolution::sql).orElse(q.queryText());
            boolean paramsResolved = resolved.isPresent();
            if (!paramsResolved) {
                log.info("  Params unresolvable — GENERIC_PLAN only, Phase 3 unavailable for this query.");
            }

            boolean isSelect = runnableSql.strip().toLowerCase().startsWith("select");
            String plan;
            try {
                plan = explainTool.explain(runnableSql, isSelect);
            } catch (Exception e) {
                log.warn("  EXPLAIN failed ({}), skipping.", e.getMessage());
                continue;
            }

            // Privacy: real cell values go to the LLM only when explicitly enabled
            // (pgagent.privacy.share-data-values). Default: sampled string literals in the
            // plan are scrubbed back to $N placeholders; plan numbers stay untouched.
            boolean shareData = props.getPrivacy().isShareDataValues();
            String planForLlm = plan;
            if (!shareData && resolved.isPresent()) {
                for (Map.Entry<Integer, String> e : resolved.get().literals().entrySet()) {
                    if (e.getValue().startsWith("'")) {
                        planForLlm = planForLlm.replace(e.getValue(), "$" + e.getKey());
                    }
                }
            }

            StringBuilder facts = new StringBuilder();
            for (String table : ParamResolver.extractTableNames(q.queryText())) {
                try {
                    facts.append(formatTableStats(tableInspectionTool.inspect(table), shareData)).append("\n");
                } catch (Exception e) {
                    log.warn("  Table inspection failed for '{}': {}", table, e.getMessage());
                }
            }

            // --- CLASSIFY ---
            // Peer queries give the classifier the context to spot N+1 parent/child relationships
            // (a child lookup whose calls ≈ parent.calls × rows-per-parent), instead of judging
            // each query in isolation.
            String peers = peerContext(candidates, q);
            String userPrompt = buildPrompt(q, planForLlm, facts.toString(), peers, paramsResolved && isSelect);
            String llmResponse;
            try {
                llmResponse = llm.complete(Prompts.DIAGNOSE_CLASSIFY_SYSTEM, userPrompt);
            } catch (Exception e) {
                log.warn("  LLM call failed: {}", e.getMessage());
                continue;
            }

            Hypothesis hypothesis = parseHypothesis(llmResponse);
            if (hypothesis == null) {
                log.warn("  LLM returned unparseable JSON — skipping this query.");
                continue;
            }

            log.info("  CLASSIFY: {} / {} (confidence {})  fix: {}",
                    hypothesis.classification(), hypothesis.pathology(), hypothesis.confidence(),
                    abbreviate(hypothesis.proposedFix()));

            if (hypothesis.classification() == Classification.APP_PROBLEM) {
                log.info("  APP_PROBLEM: no Phase 2/3 benchmarking applicable.");
            }

            // --- TEST + EVALUATE (Phase 2) ---
            // Test the hypothesis with HypoPG; when the estimate fails the bar, feed the
            // result back to the LLM for a revised hypothesis or an explicit discard.
            double minEstSpeedup = props.getLoop().getMinEstimatedSpeedup();
            int maxRetries = props.getLoop().getMaxRetriesPerQuery();
            HypoResult hypoResult = null;
            int retries = 0;

            while (hypothesis.classification() != Classification.APP_PROBLEM) {
                Optional<String> idx = extractCreateIndex(hypothesis.proposedFix());
                if (idx.isEmpty()) break;  // nothing HypoPG-testable (config/stats fix)

                try {
                    hypoResult = hypoPGTool.test(idx.get(), runnableSql);
                    log.info("  Phase 2: cost {} → {} (est. {}x)",
                            fmt(hypoResult.costBefore()), fmt(hypoResult.costAfter()),
                            fmt(hypoResult.estimatedSpeedup()));
                } catch (UnsupportedOperationException e) {
                    log.info("  Phase 2 skipped: {}", e.getMessage());
                    break;
                } catch (Exception e) {
                    log.warn("  HypoPG failed: {}", e.getMessage());
                    break;
                }

                if (hypoResult.estimatedSpeedup() >= minEstSpeedup) break;  // hypothesis holds

                if (retries >= maxRetries) {
                    log.info("  EVALUATE: estimate still below {}x after {} attempt(s) — reporting with the weak estimate attached.",
                            fmt(minEstSpeedup), retries + 1);
                    break;
                }
                retries++;
                log.info("  EVALUATE: est. {}x < required {}x — requesting revised hypothesis ({}/{}).",
                        fmt(hypoResult.estimatedSpeedup()), fmt(minEstSpeedup), retries, maxRetries);

                Revision revision = requestRevision(userPrompt, hypothesis, hypoResult, minEstSpeedup);
                if (revision == null) {
                    log.warn("  Revision unparseable — keeping previous hypothesis.");
                    break;
                }
                if (revision.discarded()) {
                    log.info("  EVALUATE: hypothesis discarded — {}", revision.reason());
                    hypothesis = new Hypothesis(
                            hypothesis.classification(), hypothesis.pathology(), hypothesis.confidence(),
                            hypothesis.evidence(),
                            revision.reason().isBlank() ? hypothesis.rootCause() : revision.reason(),
                            "No effective database-side fix found (hypothesis discarded after HypoPG testing).",
                            hypothesis.tradeoffs());
                    break;
                }
                hypothesis = revision.hypothesis();
                log.info("  REVISED: {} / {}  fix: {}", hypothesis.classification(),
                        hypothesis.pathology(), abbreviate(hypothesis.proposedFix()));
            }

            // --- VERIFY + EVALUATE (Phase 3, DB fixes only) ---
            Double beforeMs = null;
            Double afterMs = null;
            Double delta = null;
            boolean verified = false;

            Optional<String> createIndex = extractCreateIndex(hypothesis.proposedFix());
            if (hypothesis.classification() != Classification.APP_PROBLEM
                    && createIndex.isPresent() && props.getLoop().isApplyFixes() && paramsResolved) {
                int runs = props.getLoop().getBenchmarkRuns();
                try {
                    BenchmarkResult before = benchmarkTool.benchmark(runnableSql, runs);
                    beforeMs = before.meanMs();
                    log.info("  Phase 3 before: mean={} ms  min={} ms  max={} ms",
                            fmt(before.meanMs()), fmt(before.minMs()), fmt(before.maxMs()));

                    applyTool.apply(createIndex.get());

                    BenchmarkResult after = benchmarkTool.benchmark(runnableSql, runs);
                    afterMs = after.meanMs();
                    delta = beforeMs - afterMs;
                    double measuredSpeedup = afterMs > 0 ? beforeMs / afterMs : 0;

                    if (measuredSpeedup >= props.getLoop().getMinMeasuredSpeedup()) {
                        verified = true;
                        log.info("  Phase 3 after:  mean={} ms  min={} ms  max={} ms  (delta={} ms, {}x)",
                                fmt(after.meanMs()), fmt(after.minMs()), fmt(after.maxMs()),
                                fmt(delta), fmt(measuredSpeedup));
                    } else {
                        // EVALUATE: the fix did not survive contact with reality — undo it.
                        log.warn("  EVALUATE: measured {}x < required {}x — dropping index, fix not kept.",
                                fmt(measuredSpeedup), fmt(props.getLoop().getMinMeasuredSpeedup()));
                        applyTool.drop(createIndex.get());
                    }
                } catch (UnsupportedOperationException e) {
                    log.info("  Phase 3 skipped: {}", e.getMessage());
                } catch (Exception e) {
                    log.warn("  Phase 3 failed: {}", e.getMessage());
                }
            }

            // --- CROSS-CHECK ---
            // Verify the LLM's claimed signal actually exists in the data; if not, downgrade
            // confidence to LOW and annotate the evidence. Deterministic guard, not classification.
            Confidence confidence = hypothesis.confidence();
            String evidence = hypothesis.evidence();
            Optional<String> mismatch = evidenceMismatch(hypothesis, q, plan);
            if (mismatch.isPresent()) {
                confidence = Confidence.LOW;
                evidence = evidence + "  [auto-check: " + mismatch.get() + "]";
                log.warn("  CROSS-CHECK: {} — confidence downgraded to LOW.", mismatch.get());
            }

            findings.add(new Finding(
                    q,
                    hypothesis.classification(),
                    hypothesis.pathology(),
                    evidence,
                    hypothesis.rootCause(),
                    hypothesis.proposedFix(),
                    hypothesis.tradeoffs(),
                    verified,
                    hypothesis.classification() == Classification.APP_PROBLEM ? null : hypoResult,
                    beforeMs, afterMs, delta,
                    confidence));
        }

        String stopReason = iteration >= maxIter ? "max-iterations reached" : "all candidates processed";
        log.info("Loop complete — {} finding(s). Stopped: {}.", findings.size(), stopReason);

        return new AgentRunResult(findings, perception.trends(),
                perception.resolvedQueries(), perception.windowStart());
    }

    // EVALUATE: feed the failed HypoPG result back to the LLM for a revised hypothesis
    // or an explicit discard. Returns null when the response is unparseable.
    private Revision requestRevision(String originalPrompt, Hypothesis failed,
                                     HypoResult result, double requiredSpeedup) {
        String response;
        try {
            response = llm.complete(Prompts.DIAGNOSE_CLASSIFY_SYSTEM,
                    Prompts.reviseAfterHypoTest(originalPrompt, failed.proposedFix(),
                            result.costBefore(), result.costAfter(),
                            result.estimatedSpeedup(), requiredSpeedup));
        } catch (Exception e) {
            log.warn("  Revision LLM call failed: {}", e.getMessage());
            return null;
        }

        String json = extractJson(response);
        if (json == null) return null;
        try {
            JsonNode n = mapper.readTree(json);
            if (n.path("discard").asBoolean(false)) {
                return new Revision(true, n.path("reason").asText(""), null);
            }
        } catch (Exception e) {
            return null;
        }

        Hypothesis revised = parseHypothesis(response);
        return revised == null ? null : new Revision(false, "", revised);
    }

    // Strips markdown fences and extracts the first complete JSON object from the LLM response.
    private static String extractJson(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int close = s.lastIndexOf("```");
            if (nl >= 0 && close > nl) s = s.substring(nl + 1, close).strip();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    private Hypothesis parseHypothesis(String raw) {
        String json = extractJson(raw);
        if (json == null) return null;

        try {
            JsonNode n = mapper.readTree(json);

            Classification cls = parseEnum(Classification.class,
                    n.path("classification").asText(), Classification.DB_PROBLEM);
            Pathology path = parseEnum(Pathology.class,
                    n.path("pathology").asText(), Pathology.OTHER);
            Confidence conf = parseEnum(Confidence.class,
                    n.path("confidence").asText(), Confidence.MEDIUM);

            return new Hypothesis(
                    cls, path, conf,
                    n.path("evidence").asText(""),
                    n.path("root_cause").asText(""),
                    n.path("proposed_fix").asText(""),
                    n.path("tradeoffs").asText(""));
        } catch (Exception e) {
            log.warn("JSON parse error in LLM response: {}", e.getMessage());
            return null;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> cls, String value, E fallback) {
        try {
            return Enum.valueOf(cls, value.toUpperCase().replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String buildPrompt(SlowQuery q, String plan, String facts, String peers, boolean analyzed) {
        String planMode = analyzed
                ? "EXPLAIN (ANALYZE, BUFFERS) — executed with parameter values sampled from pg_stats; "
                  + "representative of real data but not the exact production values"
                : "EXPLAIN (GENERIC_PLAN) — parameters unresolved; plan structure and cost only, no runtime stats";
        return "QUERY:\n" + q.queryText() + "\n\n"
                + "STATISTICS FROM pg_stat_statements:\n"
                + "  calls=" + q.calls()
                + "  mean=" + fmt(q.meanTimeMs()) + "ms"
                + "  total=" + fmt(q.totalTimeMs()) + "ms"
                + "  rows_per_call=" + fmt(q.calls() > 0 ? (double) q.rows() / q.calls() : 0) + "\n\n"
                + "EXECUTION PLAN (" + planMode + "):\n" + plan + "\n\n"
                + (facts.isBlank() ? "" : "TABLE FACTS:\n" + facts + "\n")
                + (peers.isBlank() ? "" : "OTHER FREQUENT QUERIES IN THIS WINDOW "
                    + "(for spotting N+1 parent/child relationships):\n" + peers);
    }

    // Compact summary of the other candidates, so the classifier can correlate a child lookup
    // with the parent query that drives it.
    private static String peerContext(List<SlowQuery> candidates, SlowQuery current) {
        StringBuilder sb = new StringBuilder();
        for (SlowQuery p : candidates) {
            if (p == current) continue;
            double rpc = p.calls() > 0 ? (double) p.rows() / p.calls() : 0;
            sb.append("  calls=").append(p.calls())
              .append("  rows/call=").append(fmt(rpc))
              .append("  ").append(abbreviate(p.queryText())).append("\n");
        }
        return sb.toString();
    }

    // Deterministic sanity check: does the data actually contain the signal the LLM claimed?
    // Returns a mismatch description when it does not. Never reclassifies — only flags.
    private static Optional<String> evidenceMismatch(Hypothesis h, SlowQuery q, String plan) {
        String text = q.queryText().toLowerCase();
        String planLower = plan.toLowerCase();
        double rowsPerCall = q.calls() > 0 ? (double) q.rows() / q.calls() : 0;
        return switch (h.pathology()) {
            case N_PLUS_ONE -> q.calls() < 100
                    ? Optional.of("N_PLUS_ONE claimed but calls=" + q.calls() + " (<100)")
                    : Optional.empty();
            case IMPLICIT_CAST -> (!planLower.contains("::") && !planLower.contains("cast("))
                    ? Optional.of("IMPLICIT_CAST claimed but no cast appears in the plan")
                    : Optional.empty();
            case DEEP_OFFSET -> !text.contains("offset")
                    ? Optional.of("DEEP_OFFSET claimed but no OFFSET in the query text")
                    : Optional.empty();
            case UNBOUNDED_RESULT -> rowsPerCall < 100
                    ? Optional.of("UNBOUNDED_RESULT claimed but rows/call=" + fmt(rowsPerCall) + " (<100)")
                    : Optional.empty();
            case LEADING_WILDCARD -> !text.contains("like")
                    ? Optional.of("LEADING_WILDCARD claimed but no LIKE in the query text")
                    : Optional.empty();
            case MISSING_INDEX -> (planLower.contains("index scan") && !planLower.contains("seq scan"))
                    ? Optional.of("MISSING_INDEX claimed but the plan already uses an Index Scan")
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    // Formats the LLM-facing table facts. MCVs are real cell values (emails, names…) and
    // are included only when share-data-values is enabled.
    private static String formatTableStats(TableStats s, boolean includeDataValues) {
        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(s.tableName())
          .append("  estimated_rows=").append(s.estimatedRows())
          .append("  disk=").append(s.diskBytes() / 1024).append(" KB")
          .append("  last_analyze=").append(s.lastAnalyze() != null ? s.lastAnalyze() : "never")
          .append("\n");

        if (!s.indexDefs().isEmpty()) {
            sb.append("  Indexes:\n");
            s.indexDefs().forEach(def -> sb.append("    ").append(def).append("\n"));
        } else {
            sb.append("  Indexes: none\n");
        }

        for (ColumnStat c : s.columnStats()) {
            sb.append("    ").append(c.columnName())
              .append(": null_frac=").append(String.format("%.3f", c.nullFraction()))
              .append(" n_distinct=").append(c.nDistinct());
            if (includeDataValues && c.mostCommonVals() != null) {
                sb.append(" mcv=").append(c.mostCommonVals());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static Optional<String> extractCreateIndex(String proposedFix) {
        return Arrays.stream(proposedFix.split("\n"))
                .map(String::strip)
                .filter(line -> {
                    String up = line.toUpperCase();
                    return up.startsWith("CREATE") && up.contains("INDEX");
                })
                .findFirst();
    }

    private static String fmt(double d) {
        return String.format("%.1f", d);
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 100 ? oneLine : oneLine.substring(0, 100) + "…";
    }

    private record Hypothesis(
            Classification classification,
            Pathology pathology,
            Confidence confidence,
            String evidence,
            String rootCause,
            String proposedFix,
            String tradeoffs
    ) {}

    private record Revision(boolean discarded, String reason, Hypothesis hypothesis) {}
}
