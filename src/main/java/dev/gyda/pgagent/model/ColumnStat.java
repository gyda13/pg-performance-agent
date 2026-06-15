package dev.gyda.pgagent.model;

public record ColumnStat(
        String columnName,
        double nullFraction,
        double nDistinct, // positive = exact count; negative = fraction of total rows (pg convention)
        String mostCommonVals,
        String mostCommonFreqs
) {}
