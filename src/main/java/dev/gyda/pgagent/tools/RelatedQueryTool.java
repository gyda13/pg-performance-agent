package dev.gyda.pgagent.tools;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Searches pg_stat_statements for other queries that mention a given table or substring, with
 * their calls and rows. Lets the autonomous agent actively hunt an N+1 parent (a query whose
 * calls ≈ this lookup's calls ÷ rows-per-parent) instead of relying on a static peer summary.
 * Read-only.
 */
@Component
public class RelatedQueryTool {

    private final JdbcTemplate jdbc;

    public RelatedQueryTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String findRelated(String pattern) {
        String like = "%" + pattern.toLowerCase().strip() + "%";
        List<String> rows = jdbc.query("""
                SELECT query, calls, rows,
                       round(total_exec_time::numeric, 1) AS total_ms,
                       round(mean_exec_time::numeric, 2)  AS mean_ms
                FROM pg_stat_statements
                WHERE lower(query) LIKE ?
                ORDER BY calls DESC
                LIMIT 15
                """,
                (rs, rowNum) -> String.format("  calls=%d rows=%d total_ms=%s mean_ms=%s  %s",
                        rs.getLong("calls"), rs.getLong("rows"),
                        rs.getString("total_ms"), rs.getString("mean_ms"),
                        oneLine(rs.getString("query"))),
                like);
        return rows.isEmpty()
                ? "No queries in pg_stat_statements match: " + pattern
                : "Queries mentioning '" + pattern + "' (ranked by calls):\n" + String.join("\n", rows);
    }

    private static String oneLine(String s) {
        String line = s.replaceAll("\\s+", " ").strip();
        return line.length() <= 120 ? line : line.substring(0, 120) + "…";
    }
}
