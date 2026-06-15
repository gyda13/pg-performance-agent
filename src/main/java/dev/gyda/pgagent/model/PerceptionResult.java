package dev.gyda.pgagent.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Output of the perceive step. Trends are keyed by the candidate's query text;
 * resolvedQueries are previous findings no longer above the thresholds in this window.
 * windowStart is null on a full-history run (no snapshot baseline).
 */
public record PerceptionResult(
        List<SlowQuery> candidates,
        Map<String, QueryTrend> trends,
        List<String> resolvedQueries,
        Instant windowStart
) {}
