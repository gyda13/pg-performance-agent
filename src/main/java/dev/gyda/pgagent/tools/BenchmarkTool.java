package dev.gyda.pgagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gyda.pgagent.config.AgentProperties;
import dev.gyda.pgagent.model.BenchmarkResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.regex.Pattern;

@Component
public class BenchmarkTool {

    private static final Pattern PARAM = Pattern.compile("\\$\\d+");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentProperties props;

    public BenchmarkTool(JdbcTemplate jdbc, ObjectMapper mapper, AgentProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
    }

    public BenchmarkResult benchmark(String sql, int runs) {
        String trimmed = sql.strip();
        if (!trimmed.toLowerCase().startsWith("select")) {
            throw new IllegalArgumentException("Benchmarking refused for non-SELECT query.");
        }

        // Never benchmark with placeholder params — the numbers would not measure the real
        // query. AgentLoop resolves $N to sampled literals first (ParamResolver); if that
        // failed, refuse instead of fabricating a measurement.
        if (PARAM.matcher(trimmed).find()) {
            throw new UnsupportedOperationException(
                    "Query has unresolved $N parameters — benchmark would not measure the real query.");
        }
        String explainSql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + trimmed;
        long timeoutMs = props.getLoop().getStatementTimeoutMs();
        double[] times = new double[runs];

        return jdbc.execute((java.sql.Connection conn) -> {
            try (Statement ts = conn.createStatement()) {
                ts.execute("SET statement_timeout = '" + timeoutMs + "ms'");
            }

            // One warmup run to prime the buffer cache before measuring.
            try (Statement st = conn.createStatement()) {
                st.executeQuery(explainSql).close();
            }

            for (int i = 0; i < runs; i++) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(explainSql)) {
                    times[i] = rs.next() ? executionTime(rs.getString(1)) : -1.0;
                }
            }

            double min  = Arrays.stream(times).min().orElse(-1);
            double max  = Arrays.stream(times).max().orElse(-1);
            double mean = Arrays.stream(times).average().orElse(-1);
            return new BenchmarkResult(runs, min, max, mean);
        });
    }

    private double executionTime(String planJson) {
        try {
            JsonNode root = mapper.readTree(planJson);
            return root.get(0).path("Execution Time").asDouble(-1.0);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to parse EXPLAIN ANALYZE output", e);
        }
    }
}
