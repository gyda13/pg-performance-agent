package dev.gyda.pgagent.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Everything one agent run produced, for reporting. */
public record AgentRunResult(
        List<Finding> findings,
        Map<String, QueryTrend> trends,
        List<String> resolvedQueries,
        Instant windowStart
) {}
