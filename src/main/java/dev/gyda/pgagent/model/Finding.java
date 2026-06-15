package dev.gyda.pgagent.model;

public record Finding(
        SlowQuery query,
        Classification classification,
        Pathology pathology,
        String evidence,
        String rootCause,
        String proposedFix,
        String tradeoffs,
        boolean verified,
        HypoResult hypoTest,
        Double beforeMs,
        Double afterMs,
        Double delta
) {}
