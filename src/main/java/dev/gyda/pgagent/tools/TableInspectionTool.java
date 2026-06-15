package dev.gyda.pgagent.tools;

import dev.gyda.pgagent.model.ColumnStat;
import dev.gyda.pgagent.model.TableStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
public class TableInspectionTool {

    private final JdbcTemplate jdbc;

    public TableInspectionTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TableStats inspect(String tableName) {
        String schema = "public";
        String table = tableName;
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.", 2);
            schema = parts[0];
            table = parts[1];
        }

        long[] sizeInfo = {-1L, 0L};
        jdbc.query("""
                SELECT COALESCE(c.reltuples::bigint, -1) AS estimated_rows,
                       pg_relation_size(c.oid)           AS disk_bytes
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relname = ? AND n.nspname = ? AND c.relkind = 'r'
                """,
                rs -> {
                    sizeInfo[0] = rs.getLong("estimated_rows");
                    sizeInfo[1] = rs.getLong("disk_bytes");
                },
                table, schema);

        List<String> indexDefs = jdbc.queryForList("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = ? AND schemaname = ?
                ORDER BY indexname
                """, String.class, table, schema);

        List<ColumnStat> columnStats = jdbc.query("""
                SELECT attname, null_frac, n_distinct,
                       most_common_vals::text AS mcv,
                       most_common_freqs::text AS mcf
                FROM pg_stats
                WHERE tablename = ? AND schemaname = ?
                ORDER BY attname
                """,
                (rs, rowNum) -> new ColumnStat(
                        rs.getString("attname"),
                        rs.getDouble("null_frac"),
                        rs.getDouble("n_distinct"),
                        rs.getString("mcv"),
                        rs.getString("mcf")
                ),
                table, schema);

        Instant lastAnalyze = jdbc.query("""
                SELECT GREATEST(last_analyze, last_autoanalyze) AS last_analyze
                FROM pg_stat_user_tables
                WHERE relname = ? AND schemaname = ?
                """,
                rs -> rs.next() ? toInstant(rs.getTimestamp("last_analyze")) : null,
                table, schema);

        return new TableStats(schema + "." + table, sizeInfo[0], sizeInfo[1],
                indexDefs, columnStats, lastAnalyze);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
