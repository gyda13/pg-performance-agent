package dev.gyda.pgagent.model;

import java.util.List;
import java.util.Map;

/**
 * Point-in-time copy of the pg_stat_statements counters, keyed by normalized query text.
 * reportedQueries holds the normalized keys that were above thresholds in that run, so the
 * next run can label findings NEW vs RECURRING and list RESOLVED ones. May be null when
 * reading a snapshot written by an older version.
 */
public record StatsSnapshot(long takenAtEpochMs,
                            Map<String, Counters> queries,
                            List<String> reportedQueries) {

    public record Counters(long calls, double totalTimeMs, long rows) {}
}
