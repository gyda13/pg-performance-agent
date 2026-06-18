package dev.gyda.pgagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gyda.pgagent.model.HypoResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

@Component
public class HypoPGTool {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public HypoPGTool(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public HypoResult test(String createIndexSql, String querySql) {
        return testSet(java.util.List.of(createIndexSql), querySql);
    }

    /**
     * Tests a set of hypothetical indexes together (HypoPG creates them all, then re-plans once).
     * Lets the agent evaluate a multi-index fix — e.g. indexing both sides of a join — which a
     * single-index test cannot reveal. The returned indexDef is the joined set, for the report.
     */
    public HypoResult testSet(java.util.List<String> createIndexSqls, String querySql) {
        if (!isHypoPGAvailable()) {
            throw new UnsupportedOperationException(
                    "hypopg extension not installed. See db/init/01-init.sql for setup instructions.");
        }

        // AgentLoop passes param-resolved SQL when available; planCost falls back to
        // GENERIC_PLAN when $N placeholders remain.
        String runnable = querySql.strip().replaceAll(";\\s*$", "");
        // hypopg_create_index expects a bare statement: no trailing semicolon, and no
        // CONCURRENTLY (meaningless for a hypothetical index, rejected by hypopg).
        java.util.List<String> indexDefs = createIndexSqls.stream()
                .map(s -> s.strip().replaceAll(";\\s*$", "").replaceAll("(?i)\\s+CONCURRENTLY\\b", ""))
                .toList();

        return jdbc.execute((java.sql.Connection conn) -> {
            try {
                double costBefore = planCost(conn, runnable);

                for (String indexDef : indexDefs) {
                    try (PreparedStatement ps = conn.prepareStatement("SELECT hypopg_create_index(?)")) {
                        ps.setString(1, indexDef);
                        ps.execute();
                    }
                }

                double costAfter = planCost(conn, runnable);
                double speedup = costAfter > 0 ? costBefore / costAfter : 1.0;
                return new HypoResult(String.join("; ", indexDefs), costBefore, costAfter, speedup);
            } finally {
                // Must reset before returning the connection to the pool — hypopg indexes are session-scoped.
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT hypopg_reset()");
                } catch (Exception ignored) {}
            }
        });
    }

    private double planCost(java.sql.Connection conn, String sql) throws java.sql.SQLException {
        // GENERIC_PLAN (PG16+) lets the planner cost a query with unresolved $N params.
        String prefix = sql.matches("(?s).*\\$\\d+.*")
                ? "EXPLAIN (GENERIC_PLAN, FORMAT JSON) "
                : "EXPLAIN (FORMAT JSON) ";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(prefix + sql)) {
            if (!rs.next()) return -1.0;
            try {
                JsonNode root = mapper.readTree(rs.getString(1));
                return root.get(0).path("Plan").path("Total Cost").asDouble(-1.0);
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to parse EXPLAIN output", e);
            }
        }
    }

    private boolean isHypoPGAvailable() {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*)::int FROM pg_extension WHERE extname = 'hypopg'",
                    Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
