package dev.gyda.pgagent.model;

import java.time.Instant;
import java.util.List;

public record TableStats(
        String tableName,
        long estimatedRows,
        long diskBytes,
        List<String> indexDefs,
        List<ColumnStat> columnStats,
        Instant lastAnalyze
) {}
