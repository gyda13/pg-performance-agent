package dev.gyda.pgagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gyda.pgagent.config.AgentProperties;
import dev.gyda.pgagent.interaction.ApplyApproval;
import dev.gyda.pgagent.llm.AgentTurn;
import dev.gyda.pgagent.llm.LlmClient;
import dev.gyda.pgagent.llm.Prompts;
import dev.gyda.pgagent.llm.ToolCall;
import dev.gyda.pgagent.llm.ToolSpec;
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
import dev.gyda.pgagent.tools.RelatedQueryTool;
import dev.gyda.pgagent.tools.TableInspectionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM-driven variant of {@link AgentLoop}. Java still owns the entry point (perceive) and every
 * measurement (the tools), but the model decides which tools to call, in what order, and when to
 * stop. Enabled by {@code pgagent.loop.autonomous=true}; otherwise the deterministic loop runs.
 *
 * Safety is preserved by keeping the guards on the tools themselves: the only writing tool
 * ({@link ApplyTool}) still refuses anything but CREATE INDEX and obeys apply-fixes, and a hard
 * {@code max-tool-calls} ceiling guarantees the run always halts. Numbers never come from the LLM —
 * measured values are recorded from tool results and attached to findings by Java.
 */
@Component
public class AutonomousAgentLoop implements PerformanceAgent {

    private static final Logger log = LoggerFactory.getLogger(AutonomousAgentLoop.class);

    private final dev.gyda.pgagent.tools.SlowQueryTool slowQueryTool;
    private final ExplainTool explainTool;
    private final TableInspectionTool tableInspectionTool;
    private final ParamResolver paramResolver;
    private final HypoPGTool hypoPGTool;
    private final BenchmarkTool benchmarkTool;
    private final ApplyTool applyTool;
    private final RelatedQueryTool relatedQueryTool;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final ApplyApproval approval;

    public AutonomousAgentLoop(dev.gyda.pgagent.tools.SlowQueryTool slowQueryTool,
                               ExplainTool explainTool,
                               TableInspectionTool tableInspectionTool,
                               ParamResolver paramResolver,
                               HypoPGTool hypoPGTool,
                               BenchmarkTool benchmarkTool,
                               ApplyTool applyTool,
                               RelatedQueryTool relatedQueryTool,
                               LlmClient llm,
                               ObjectMapper mapper,
                               AgentProperties props,
                               ApplyApproval approval) {
        this.slowQueryTool = slowQueryTool;
        this.explainTool = explainTool;
        this.tableInspectionTool = tableInspectionTool;
        this.paramResolver = paramResolver;
        this.hypoPGTool = hypoPGTool;
        this.benchmarkTool = benchmarkTool;
        this.applyTool = applyTool;
        this.relatedQueryTool = relatedQueryTool;
        this.llm = llm;
        this.mapper = mapper;
        this.props = props;
        this.approval = approval;
    }

    /** Measured numbers captured from tool results, keyed by the query text the model passed. */
    private record BenchRecord(Double beforeMs, Double afterMs, Double delta, boolean verified) {}

    @Override
    public AgentRunResult run() {
        log.info("Autonomous (LLM-driven) loop active.");

        PerceptionResult perception = slowQueryTool.getSlowQueries();
        List<SlowQuery> candidates = perception.candidates();
        log.info("Found {} candidate slow queries (mean >= {} ms OR total >= {} ms).",
                candidates.size(), props.getLoop().getMinMeanTimeMs(), props.getLoop().getMinTotalTimeMs());

        if (candidates.isEmpty()) {
            log.info("No slow queries found. Populate pg_stat_statements by running the workload first.");
            return new AgentRunResult(List.of(), perception.trends(),
                    perception.resolvedQueries(), perception.windowStart());
        }

        Map<String, HypoResult> hypoByQuery = new HashMap<>();
        Map<String, BenchRecord> benchByQuery = new HashMap<>();
        List<ToolSpec> tools = catalog();

        List<JsonNode> messages = new ArrayList<>();
        messages.add(userMessage(initialContext(candidates)));

        List<Finding> findings = List.of();
        int max = props.getLoop().getMaxToolCalls();
        String stopReason = "max-tool-calls reached";
        boolean submitted = false;

        for (int turn = 1; turn <= max; turn++) {
            AgentTurn t;
            try {
                t = llm.converse(Prompts.AUTONOMOUS_SYSTEM, tools, messages);
            } catch (Exception e) {
                log.warn("  LLM turn failed: {} — ending run.", e.getMessage());
                stopReason = "LLM error";
                break;
            }
            messages.add(assistantMessage(t.assistantContent()));
            if (!t.text().isBlank()) {
                // Full reasoning — the model's thinking is the most interesting part of an
                // LLM-driven run, so it is logged verbatim rather than abbreviated.
                log.info("  [{}/{}] reasoning: {}", turn, max, t.text().strip());
            }
            if ("max_tokens".equals(t.stopReason())) {
                log.warn("  Turn hit the max_tokens limit — response truncated. Raise "
                        + "pgagent.anthropic.max-tool-tokens if findings come back empty.");
            }

            if (!t.wantsTools()) {
                log.info("  Model ended its turn without calling a tool (stop_reason={}).", t.stopReason());
                stopReason = "model ended without submit_findings";
                break;
            }

            ObjectNode toolResults = mapper.createObjectNode();
            toolResults.put("role", "user");
            ArrayNode resultArr = toolResults.putArray("content");

            JsonNode submittedInput = null;
            for (ToolCall call : t.toolCalls()) {
                String result;
                if ("submit_findings".equals(call.name())) {
                    submittedInput = call.input();
                    result = "Findings recorded — run complete.";
                } else {
                    result = dispatch(call, hypoByQuery, benchByQuery);
                }
                resultArr.add(toolResultBlock(call.id(), result));
            }
            messages.add(toolResults);

            if (submittedInput != null) {
                findings = buildFindings(submittedInput, candidates, hypoByQuery, benchByQuery);
                submitted = true;
                stopReason = "submit_findings";
                break;
            }
        }

        log.info("Autonomous loop complete — {} finding(s). Stopped: {}.",
                findings.size(), stopReason);
        if (!submitted) {
            log.warn("  Model did not call submit_findings — reporting whatever it produced (may be empty).");
        }

        return new AgentRunResult(findings, perception.trends(),
                perception.resolvedQueries(), perception.windowStart());
    }

    // ---------------------------------------------------------------- tool dispatch

    private String dispatch(ToolCall call, Map<String, HypoResult> hypoByQuery,
                            Map<String, BenchRecord> benchByQuery) {
        try {
            return switch (call.name()) {
                case "explain_query" -> explainQuery(call.input().path("query").asText());
                case "inspect_table" -> inspectTable(call.input().path("table").asText());
                case "test_hypothetical_index" -> testIndex(
                        call.input().path("query").asText(),
                        call.input().path("create_index_sql").asText(),
                        hypoByQuery);
                case "benchmark_and_apply_index" -> benchmarkApply(
                        call.input().path("query").asText(),
                        call.input().path("create_index_sql").asText(),
                        benchByQuery, hypoByQuery);
                case "find_related_queries" -> relatedQueryTool.findRelated(
                        call.input().path("pattern").asText());
                case "column_distribution" -> tableInspectionTool.columnDistribution(
                        call.input().path("table").asText(),
                        call.input().path("column").asText(),
                        props.getPrivacy().isShareDataValues());
                case "test_index_set" -> testIndexSet(
                        call.input().path("query").asText(),
                        call.input().path("create_index_sqls"),
                        hypoByQuery);
                case "explain_with_value" -> explainWithValue(
                        call.input().path("query").asText(),
                        call.input().path("param_index").asInt(),
                        call.input().path("value").asText());
                case "analyze_table_and_recheck" -> analyzeAndRecheck(
                        call.input().path("table").asText(),
                        call.input().path("query").asText());
                default -> "Unknown tool: " + call.name();
            };
        } catch (Exception e) {
            return "Tool '" + call.name() + "' failed: " + e.getMessage();
        }
    }

    private String explainQuery(String query) {
        Optional<ParamResolver.Resolution> resolved = paramResolver.resolve(query);
        String runnable = resolved.map(ParamResolver.Resolution::sql).orElse(query);
        boolean isSelect = runnable.strip().toLowerCase().startsWith("select");
        String plan;
        try {
            plan = explainTool.explain(runnable, isSelect);
        } catch (Exception e) {
            return "EXPLAIN failed: " + e.getMessage();
        }
        // Privacy: scrub sampled string literals back to $N unless sharing is enabled.
        if (!props.getPrivacy().isShareDataValues() && resolved.isPresent()) {
            for (Map.Entry<Integer, String> e : resolved.get().literals().entrySet()) {
                if (e.getValue().startsWith("'")) {
                    plan = plan.replace(e.getValue(), "$" + e.getKey());
                }
            }
        }
        String mode = resolved.isPresent()
                ? "EXPLAIN (ANALYZE, BUFFERS) — params sampled from pg_stats (may differ from production)"
                : "EXPLAIN (GENERIC_PLAN) — params unresolved; structure and cost only, benchmarking unavailable";
        return mode + "\n" + plan;
    }

    private String inspectTable(String table) {
        TableStats s = tableInspectionTool.inspect(table);
        boolean shareData = props.getPrivacy().isShareDataValues();
        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(s.tableName())
          .append("  estimated_rows=").append(s.estimatedRows())
          .append("  disk=").append(s.diskBytes() / 1024).append(" KB")
          .append("  last_analyze=").append(s.lastAnalyze() != null ? s.lastAnalyze() : "never").append("\n");
        sb.append(s.indexDefs().isEmpty() ? "  Indexes: none\n" : "  Indexes:\n");
        s.indexDefs().forEach(def -> sb.append("    ").append(def).append("\n"));
        for (ColumnStat c : s.columnStats()) {
            sb.append("    ").append(c.columnName())
              .append(": null_frac=").append(String.format("%.3f", c.nullFraction()))
              .append(" n_distinct=").append(c.nDistinct());
            if (shareData && c.mostCommonVals() != null) {
                sb.append(" mcv=").append(c.mostCommonVals());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String testIndex(String query, String createIndexSql, Map<String, HypoResult> hypoByQuery) {
        String runnable = paramResolver.resolve(query).map(ParamResolver.Resolution::sql).orElse(query);
        HypoResult r = hypoPGTool.test(createIndexSql, runnable);
        hypoByQuery.put(query, r);
        ObjectNode out = mapper.createObjectNode();
        out.put("plan_cost_before", r.costBefore());
        out.put("plan_cost_after", r.costAfter());
        out.put("estimated_speedup", r.estimatedSpeedup());
        out.put("min_estimated_speedup_bar", props.getLoop().getMinEstimatedSpeedup());
        out.put("clears_bar", r.estimatedSpeedup() >= props.getLoop().getMinEstimatedSpeedup());
        return out.toString();
    }

    private String benchmarkApply(String query, String createIndexSql,
                                  Map<String, BenchRecord> benchByQuery, Map<String, HypoResult> hypoByQuery) {
        if (!props.getLoop().isApplyFixes()) {
            return "skipped: apply-fixes is disabled (read-only). Cannot benchmark or apply an index.";
        }
        // Validate BEFORE prompting the user: ApplyTool only runs a bare CREATE INDEX, so reject
        // multi-statement DDL or anything needing a CREATE EXTENSION here rather than asking the
        // user to approve a write that would then fail.
        if (!ApplyTool.isApplicable(createIndexSql)) {
            return "skipped: not a standalone CREATE INDEX (multi-statement, or needs a CREATE EXTENSION). "
                    + "Propose a single CREATE INDEX, or report this as an application/extension recommendation instead.";
        }
        Optional<ParamResolver.Resolution> resolved = paramResolver.resolve(query);
        if (resolved.isEmpty()) {
            return "skipped: parameters could not be resolved, so the query cannot be benchmarked.";
        }
        // Human-in-the-loop: preview the estimate (if HypoPG ran) and require approval before writing.
        HypoResult prior = lookup(hypoByQuery, query);
        Double est = prior != null ? prior.estimatedSpeedup() : null;
        if (!approval.approve("index", createIndexSql, est)) {
            return "skipped: the user declined to apply this index. The database was not modified.";
        }
        String runnable = resolved.get().sql();
        int runs = props.getLoop().getBenchmarkRuns();

        BenchmarkResult before = benchmarkTool.benchmark(runnable, runs);
        applyTool.apply(createIndexSql);
        BenchmarkResult after = benchmarkTool.benchmark(runnable, runs);
        double delta = before.meanMs() - after.meanMs();
        double speedup = after.meanMs() > 0 ? before.meanMs() / after.meanMs() : 0;
        boolean verified = speedup >= props.getLoop().getMinMeasuredSpeedup();
        if (!verified) {
            applyTool.drop(createIndexSql);   // EVALUATE: did not help → undo
        }
        benchByQuery.put(query, new BenchRecord(before.meanMs(), after.meanMs(), delta, verified));

        ObjectNode out = mapper.createObjectNode();
        out.put("before_ms", before.meanMs());
        out.put("after_ms", after.meanMs());
        out.put("delta_ms", delta);
        out.put("measured_speedup", speedup);
        out.put("kept", verified);
        out.put("note", verified ? "index applied and kept" : "index dropped — improvement below the bar");
        return out.toString();
    }

    private String testIndexSet(String query, JsonNode createIndexSqls, Map<String, HypoResult> hypoByQuery) {
        List<String> idxs = new ArrayList<>();
        if (createIndexSqls.isArray()) {
            createIndexSqls.forEach(n -> idxs.add(n.asText()));
        } else if (!createIndexSqls.isMissingNode()) {
            idxs.add(createIndexSqls.asText());
        }
        if (idxs.isEmpty()) {
            return "no index statements supplied.";
        }
        String runnable = paramResolver.resolve(query).map(ParamResolver.Resolution::sql).orElse(query);
        HypoResult r = hypoPGTool.testSet(idxs, runnable);
        hypoByQuery.put(query, r);
        ObjectNode out = mapper.createObjectNode();
        out.put("indexes", String.join("; ", idxs));
        out.put("plan_cost_before", r.costBefore());
        out.put("plan_cost_after", r.costAfter());
        out.put("estimated_speedup", r.estimatedSpeedup());
        out.put("clears_bar", r.estimatedSpeedup() >= props.getLoop().getMinEstimatedSpeedup());
        return out.toString();
    }

    private String explainWithValue(String query, int paramIndex, String value) {
        // Re-explain with a caller-supplied literal instead of a sampled one — used to confirm a
        // LEADING_WILDCARD / IMPLICIT_CAST hypothesis with the actual suspected value. ExplainTool
        // still enforces SELECT-only and rejects multi-statement input, so this stays read-only.
        String substituted = query.replaceAll("\\$" + paramIndex + "(?!\\d)",
                java.util.regex.Matcher.quoteReplacement(value));
        boolean isSelect = substituted.strip().toLowerCase().startsWith("select");
        String plan = explainTool.explain(substituted, isSelect);
        return "EXPLAIN with $" + paramIndex + " = " + value + ":\n" + plan;
    }

    private String analyzeAndRecheck(String table, String query) {
        if (!props.getLoop().isApplyFixes()) {
            return "skipped: apply-fixes is disabled (read-only). Cannot run ANALYZE.";
        }
        if (!approval.approve("ANALYZE", "ANALYZE " + table, null)) {
            return "skipped: the user declined to run ANALYZE. The database was not modified.";
        }
        applyTool.analyze(table);
        String runnable = paramResolver.resolve(query).map(ParamResolver.Resolution::sql).orElse(query);
        boolean isSelect = runnable.strip().toLowerCase().startsWith("select");
        String plan = explainTool.explain(runnable, isSelect);
        return "ANALYZE " + table + " complete. Fresh plan (compare estimated vs actual rows):\n" + plan;
    }

    // ---------------------------------------------------------------- findings

    private List<Finding> buildFindings(JsonNode input, List<SlowQuery> candidates,
                                        Map<String, HypoResult> hypoByQuery,
                                        Map<String, BenchRecord> benchByQuery) {
        List<Finding> out = new ArrayList<>();
        for (JsonNode f : input.path("findings")) {
            String query = f.path("query").asText();
            Classification cls = parseEnum(Classification.class, f.path("classification").asText(), Classification.DB_PROBLEM);
            Pathology path = parseEnum(Pathology.class, f.path("pathology").asText(), Pathology.OTHER);
            Confidence conf = parseEnum(Confidence.class, f.path("confidence").asText(), Confidence.MEDIUM);

            SlowQuery sq = matchCandidate(candidates, query);

            // Rule #2: app-layer fixes are never verified and carry no measurement.
            HypoResult hypo = null;
            Double beforeMs = null, afterMs = null, delta = null;
            boolean verified = false;
            if (cls != Classification.APP_PROBLEM) {
                hypo = lookup(hypoByQuery, query);
                BenchRecord b = lookup(benchByQuery, query);
                if (b != null) {
                    beforeMs = b.beforeMs();
                    afterMs = b.afterMs();
                    delta = b.delta();
                    verified = b.verified();
                }
            }

            out.add(new Finding(sq, cls, path,
                    f.path("evidence").asText(""),
                    f.path("root_cause").asText(""),
                    f.path("proposed_fix").asText(""),
                    f.path("tradeoffs").asText(""),
                    verified, hypo, beforeMs, afterMs, delta, conf));
        }
        return out;
    }

    private static SlowQuery matchCandidate(List<SlowQuery> candidates, String query) {
        for (SlowQuery c : candidates) {
            if (c.queryText().equals(query)) return c;
        }
        String norm = query.replaceAll("\\s+", " ").strip().toLowerCase();
        for (SlowQuery c : candidates) {
            String cn = c.queryText().replaceAll("\\s+", " ").strip().toLowerCase();
            if (cn.contains(norm) || norm.contains(cn)) return c;
        }
        // Model referenced a query we never perceived — keep the text, zero the (unmeasured) stats.
        return new SlowQuery(query, 0, 0, 0, 0);
    }

    private static <T> T lookup(Map<String, T> map, String query) {
        T v = map.get(query);
        if (v != null) return v;
        String norm = query.replaceAll("\\s+", " ").strip().toLowerCase();
        for (Map.Entry<String, T> e : map.entrySet()) {
            String kn = e.getKey().replaceAll("\\s+", " ").strip().toLowerCase();
            if (kn.contains(norm) || norm.contains(kn)) return e.getValue();
        }
        return null;
    }

    // ---------------------------------------------------------------- tool catalog

    private List<ToolSpec> catalog() {
        return List.of(
                new ToolSpec("explain_query",
                        "Run EXPLAIN on a query and return the real execution plan.",
                        objectSchema(Map.of("query", "The query text to explain (the $N form is fine)."),
                                List.of("query"))),
                new ToolSpec("inspect_table",
                        "Return a table's size, indexes, per-column stats, and last ANALYZE time.",
                        objectSchema(Map.of("table", "Table name, optionally schema-qualified."),
                                List.of("table"))),
                new ToolSpec("test_hypothetical_index",
                        "Use HypoPG to virtually create an index and report planner cost before/after. "
                        + "Does not touch the database. Use to check a DB fix before proposing it.",
                        objectSchema(Map.of(
                                "query", "The query the index should help.",
                                "create_index_sql", "A single CREATE INDEX statement to test."),
                                List.of("query", "create_index_sql"))),
                new ToolSpec("benchmark_and_apply_index",
                        "Phase 3: time the query, really create the index, time again, and keep it only "
                        + "if it actually helped. May be disabled (read-only) or require user approval.",
                        objectSchema(Map.of(
                                "query", "The query to benchmark.",
                                "create_index_sql", "The single CREATE INDEX statement to apply."),
                                List.of("query", "create_index_sql"))),
                new ToolSpec("find_related_queries",
                        "Search pg_stat_statements for other queries mentioning a table or substring, "
                        + "with their calls/rows. Use to find an N+1 parent that drives a single-row lookup.",
                        objectSchema(Map.of("pattern", "A table name or SQL substring to search for."),
                                List.of("pattern"))),
                new ToolSpec("column_distribution",
                        "Selectivity of one column: n_distinct, null fraction, and (if sharing is on) "
                        + "the most-common values. Use to judge whether an index would even help.",
                        objectSchema(Map.of(
                                "table", "Table name, optionally schema-qualified.",
                                "column", "Column to profile."),
                                List.of("table", "column"))),
                new ToolSpec("test_index_set",
                        "Like test_hypothetical_index but for SEVERAL indexes at once (HypoPG creates "
                        + "them all, then re-plans). Use for join/MIXED cases needing indexes on both sides.",
                        indexSetSchema()),
                new ToolSpec("explain_with_value",
                        "Re-EXPLAIN the query substituting a specific literal for a $N parameter, to "
                        + "confirm a LEADING_WILDCARD or IMPLICIT_CAST hypothesis with the real value. Read-only.",
                        explainWithValueSchema()),
                new ToolSpec("analyze_table_and_recheck",
                        "Run ANALYZE on a table to refresh planner statistics (the STALE_STATS fix), then "
                        + "re-EXPLAIN the query so you can compare estimated vs actual rows. Requires approval.",
                        objectSchema(Map.of(
                                "table", "Table to ANALYZE.",
                                "query", "The query to re-EXPLAIN afterwards."),
                                List.of("table", "query"))),
                new ToolSpec("submit_findings",
                        "Report your conclusions and end the run. Call exactly once when done.",
                        findingsSchema()));
    }

    private JsonNode objectSchema(Map<String, String> properties, List<String> required) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        properties.forEach((name, desc) -> {
            ObjectNode p = props.putObject(name);
            p.put("type", "string");
            p.put("description", desc);
        });
        ArrayNode req = schema.putArray("required");
        required.forEach(req::add);
        return schema;
    }

    private JsonNode indexSetSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "The query the indexes should help.");
        ObjectNode arr = props.putObject("create_index_sqls");
        arr.put("type", "array");
        arr.put("description", "One or more CREATE INDEX statements to test together.");
        arr.putObject("items").put("type", "string");
        schema.putArray("required").add("query").add("create_index_sqls");
        return schema;
    }

    private JsonNode explainWithValueSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "The query containing the $N parameter.");
        props.putObject("param_index").put("type", "integer").put("description", "Which parameter to replace (the N in $N).");
        props.putObject("value").put("type", "string").put("description", "The literal to substitute (e.g. '%@example.com').");
        schema.putArray("required").add("query").add("param_index").add("value");
        return schema;
    }

    private JsonNode findingsSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode findings = props.putObject("findings");
        findings.put("type", "array");
        ObjectNode item = findings.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        for (String field : List.of("query", "classification", "pathology", "confidence",
                "evidence", "root_cause", "proposed_fix", "tradeoffs")) {
            ip.putObject(field).put("type", "string");
        }
        ArrayNode itemReq = item.putArray("required");
        List.of("query", "classification", "pathology", "proposed_fix").forEach(itemReq::add);
        schema.putArray("required").add("findings");
        return schema;
    }

    // ---------------------------------------------------------------- message helpers

    private String initialContext(List<SlowQuery> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("These are the most expensive queries on the database (from pg_stat_statements, ")
          .append("ranked by total time). Investigate each with the tools and then call ")
          .append("submit_findings. Numbers below are real; do not alter them.\n\n");
        int i = 0;
        for (SlowQuery q : candidates) {
            i++;
            double rpc = q.calls() > 0 ? (double) q.rows() / q.calls() : 0;
            sb.append("[").append(i).append("] ").append(q.queryText()).append("\n")
              .append("    calls=").append(q.calls())
              .append("  mean_ms=").append(fmt(q.meanTimeMs()))
              .append("  total_ms=").append(fmt(q.totalTimeMs()))
              .append("  rows_per_call=").append(fmt(rpc)).append("\n");
        }
        return sb.toString();
    }

    private JsonNode userMessage(String text) {
        ObjectNode m = mapper.createObjectNode();
        m.put("role", "user");
        m.put("content", text);
        return m;
    }

    private JsonNode assistantMessage(JsonNode content) {
        ObjectNode m = mapper.createObjectNode();
        m.put("role", "assistant");
        m.set("content", content);
        return m;
    }

    private JsonNode toolResultBlock(String toolUseId, String content) {
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", content);
        return block;
    }

    // ---------------------------------------------------------------- misc

    private static <E extends Enum<E>> E parseEnum(Class<E> cls, String value, E fallback) {
        try {
            return Enum.valueOf(cls, value.toUpperCase().replace(" ", "_"));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String fmt(double d) {
        return String.format("%.1f", d);
    }
}
