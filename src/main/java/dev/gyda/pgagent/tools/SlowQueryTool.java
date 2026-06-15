package dev.gyda.pgagent.tools;

import dev.gyda.pgagent.config.AgentProperties;
import dev.gyda.pgagent.model.PerceptionResult;
import dev.gyda.pgagent.model.QueryTrend;
import dev.gyda.pgagent.model.SlowQuery;
import dev.gyda.pgagent.model.StatsSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SlowQueryTool {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryTool.class);

    private final JdbcTemplate jdbc;
    private final AgentProperties props;
    private final SnapshotTool snapshotTool;

    public SlowQueryTool(JdbcTemplate jdbc, AgentProperties props, SnapshotTool snapshotTool) {
        this.jdbc = jdbc;
        this.props = props;
        this.snapshotTool = snapshotTool;
    }

    public PerceptionResult getSlowQueries() {
        int limit = props.getLoop().getSlowQueryLimit();
        double minMeanMs = props.getLoop().getMinMeanTimeMs();
        double minTotalMs = props.getLoop().getMinTotalTimeMs();

        // Thresholds are applied after snapshot diffing — they must filter the window's
        // delta, not the cumulative counters — so the SQL fetches all candidates.
        // SELECT/WITH only: DDL, DO blocks, ANALYZE are not diagnosable.
        List<SlowQuery> raw = jdbc.query("""
                SELECT query, calls, total_exec_time, mean_exec_time, rows
                FROM pg_stat_statements
                WHERE query NOT ILIKE '%pg_stat_statements%'
                  AND (ltrim(query) ILIKE 'SELECT%' OR ltrim(query) ILIKE 'WITH%')
                """,
                (rs, rowNum) -> new SlowQuery(
                        rs.getString("query"),
                        rs.getLong("calls"),
                        rs.getDouble("total_exec_time"),
                        rs.getDouble("mean_exec_time"),
                        rs.getLong("rows")
                ));

        Map<String, SlowQuery> cumulative = merge(raw);

        // Time window: when a snapshot from the previous run exists, the numbers mean
        // "since last run" instead of "since pg_stat_statements was reset".
        Optional<StatsSnapshot> baseline = snapshotTool.load();
        Map<String, SlowQuery> windowed = baseline
                .map(b -> subtract(cumulative, b))
                .orElse(cumulative);
        baseline.ifPresent(b -> log.info("Analyzing activity since {} (snapshot diff).",
                Instant.ofEpochMilli(b.takenAtEpochMs())));

        List<SlowQuery> candidates = windowed.values().stream()
                .filter(q -> q.meanTimeMs() >= minMeanMs || q.totalTimeMs() >= minTotalMs)
                .sorted(Comparator.comparingDouble(SlowQuery::totalTimeMs).reversed())
                .limit(limit)
                .toList();

        // Trend vs the previous run's report: which candidates were already flagged last
        // time (RECURRING — still not fixed), which are NEW, and which previous findings
        // are no longer above the thresholds (RESOLVED — fixed, or simply idle this window).
        Set<String> currentKeys = candidates.stream()
                .map(q -> normalize(q.queryText()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, QueryTrend> trends = new LinkedHashMap<>();
        List<String> resolved = List.of();
        if (baseline.isPresent() && baseline.get().reportedQueries() != null) {
            Set<String> previous = new HashSet<>(baseline.get().reportedQueries());
            for (SlowQuery q : candidates) {
                trends.put(q.queryText(),
                        previous.contains(normalize(q.queryText())) ? QueryTrend.RECURRING : QueryTrend.NEW);
            }
            resolved = previous.stream().filter(k -> !currentKeys.contains(k)).toList();
            if (!resolved.isEmpty()) {
                log.info("{} previous finding(s) no longer above thresholds — resolved or idle this window.",
                        resolved.size());
            }
        }

        snapshotTool.save(toCounters(cumulative), List.copyOf(currentKeys));

        return new PerceptionResult(candidates, trends, resolved,
                baseline.map(b -> Instant.ofEpochMilli(b.takenAtEpochMs())).orElse(null));
    }

    // pg_stat_statements assigns different queryids to the same logical query when it runs
    // from different contexts (e.g. DO block vs direct client). Merge those entries:
    // sum calls + totals, recalculate mean, so the agent sees each query exactly once.
    private static Map<String, SlowQuery> merge(List<SlowQuery> raw) {
        Map<String, SlowQuery> seen = new LinkedHashMap<>();
        for (SlowQuery q : raw) {
            String key = normalize(q.queryText());
            SlowQuery prev = seen.get(key);
            if (prev == null) {
                seen.put(key, q);
            } else {
                long calls = prev.calls() + q.calls();
                double total = prev.totalTimeMs() + q.totalTimeMs();
                long rows = prev.rows() + q.rows();
                seen.put(key, new SlowQuery(
                        prev.queryText(), calls, total,
                        calls > 0 ? total / calls : 0, rows));
            }
        }
        return seen;
    }

    private static Map<String, SlowQuery> subtract(Map<String, SlowQuery> cumulative,
                                                   StatsSnapshot baseline) {
        Map<String, SlowQuery> out = new LinkedHashMap<>();
        for (Map.Entry<String, SlowQuery> e : cumulative.entrySet()) {
            SlowQuery q = e.getValue();
            StatsSnapshot.Counters b = baseline.queries().get(e.getKey());
            if (b == null) {
                out.put(e.getKey(), q);  // first seen after the snapshot
                continue;
            }
            long calls = q.calls() - b.calls();
            double total = q.totalTimeMs() - b.totalTimeMs();
            long rows = q.rows() - b.rows();
            if (calls < 0 || total < 0) {
                // Counters went backwards: pg_stat_statements was reset after the snapshot,
                // so the cumulative numbers already describe only the window.
                out.put(e.getKey(), q);
            } else if (calls > 0) {
                out.put(e.getKey(), new SlowQuery(q.queryText(), calls, total, total / calls, rows));
            }
            // calls == 0: not executed in the window — nothing to diagnose.
        }
        return out;
    }

    private static Map<String, StatsSnapshot.Counters> toCounters(Map<String, SlowQuery> cumulative) {
        return cumulative.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> new StatsSnapshot.Counters(
                        e.getValue().calls(), e.getValue().totalTimeMs(), e.getValue().rows()),
                (a, b) -> a,
                LinkedHashMap::new));
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").strip().toLowerCase();
    }
}
