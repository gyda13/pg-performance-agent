package dev.gyda.pgagent.tools;

import dev.gyda.pgagent.config.AgentProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Pattern;

@Component
public class ExplainTool {

    private static final Pattern PARAM = Pattern.compile("\\$\\d+");

    private final JdbcTemplate jdbc;
    private final AgentProperties props;

    public ExplainTool(JdbcTemplate jdbc, AgentProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public String explain(String sql, boolean analyze) {
        String trimmed = sql.strip().replaceAll(";\\s*$", "");
        if (trimmed.contains(";")) {
            throw new IllegalArgumentException("Multi-statement input refused.");
        }
        if (analyze && !trimmed.toLowerCase().startsWith("select")) {
            throw new IllegalArgumentException(
                    "EXPLAIN ANALYZE refused for non-SELECT query. Pass analyze=false for Phase 1.");
        }

        // Unresolved $N params cannot execute. GENERIC_PLAN (PG16+) returns the planner's
        // real generic plan without running the query — no NULL-substitution artifacts.
        String prefix;
        if (PARAM.matcher(trimmed).find()) {
            prefix = "EXPLAIN (GENERIC_PLAN, FORMAT JSON) ";
        } else if (analyze) {
            prefix = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ";
        } else {
            prefix = "EXPLAIN (FORMAT JSON) ";
        }
        long timeoutMs = props.getLoop().getStatementTimeoutMs();

        return jdbc.execute((java.sql.Connection conn) -> {
            boolean autoCommit = conn.getAutoCommit();
            // Transaction + ROLLBACK: second defense layer behind the SELECT-only guard,
            // since EXPLAIN ANALYZE executes the statement it explains.
            conn.setAutoCommit(false);
            try {
                try (Statement ts = conn.createStatement()) {
                    ts.execute("SET statement_timeout = '" + timeoutMs + "ms'");
                }
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(prefix + trimmed)) {
                    return rs.next() ? rs.getString(1) : "[]";
                }
            } finally {
                conn.rollback();
                conn.setAutoCommit(autoCommit);
            }
        });
    }
}
