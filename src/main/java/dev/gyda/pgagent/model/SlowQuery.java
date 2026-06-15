package dev.gyda.pgagent.model;

public record SlowQuery(
        String queryText,
        long calls,
        double totalTimeMs,
        double meanTimeMs,
        long rows
) {}
