package dev.gyda.pgagent.model;

public record BenchmarkResult(
        int runs,
        double minMs,
        double maxMs,
        double meanMs
) {}
