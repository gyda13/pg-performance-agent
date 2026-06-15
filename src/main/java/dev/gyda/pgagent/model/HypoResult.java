package dev.gyda.pgagent.model;

public record HypoResult(
        String indexDef,
        double costBefore,
        double costAfter,
        double estimatedSpeedup
) {}
