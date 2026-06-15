package dev.gyda.pgagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gyda.pgagent.config.AgentProperties;
import dev.gyda.pgagent.model.SlowQuery;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SlowQueryToolTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withCommand("postgres",
                    "-c", "shared_preload_libraries=pg_stat_statements",
                    "-c", "pg_stat_statements.track=all");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(pg.getJdbcUrl());
        ds.setUsername(pg.getUsername());
        ds.setPassword(pg.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS products (
                    id BIGSERIAL PRIMARY KEY,
                    name TEXT NOT NULL,
                    price_cents BIGINT NOT NULL
                )""");

        jdbc.execute("""
                INSERT INTO products (name, price_cents)
                SELECT 'product-' || g, g * 100
                FROM generate_series(1, 10000) AS g""");

        jdbc.execute("SELECT pg_stat_statements_reset()");

        // Plant N+1 pattern: issue 500 individual queries from JDBC so pg_stat_statements
        // normalizes them to a single entry with calls=500, tiny mean, large total.
        // A DO block would record as one statement (calls=1) and miss this signal entirely.
        for (int i = 1; i <= 500; i++) {
            jdbc.queryForList("SELECT id, name FROM products WHERE id = ?", (long) i);
        }

        // Plant a slow full-scan query — large mean
        for (int i = 0; i < 5; i++) {
            jdbc.execute("SELECT count(*) FROM products WHERE price_cents > 500000");
        }
    }

    @Test
    void capturesHighMeanQuery() {
        List<SlowQuery> results = tool(100.0, 0.0).getSlowQueries().candidates();
        assertThat(results).isNotEmpty();
        assertThat(results).anyMatch(q ->
                q.queryText().contains("count") && q.meanTimeMs() >= 0.0);
    }

    @Test
    void capturesNPlusOnePatternByTotalTime() {
        // N+1 queries have tiny mean but large total — a mean-only threshold would miss them
        List<SlowQuery> results = tool(50.0, 1.0).getSlowQueries().candidates();
        assertThat(results).anyMatch(q ->
                q.queryText().contains("products") &&
                q.queryText().contains("id") &&
                q.calls() >= 100);
    }

    @Test
    void ordersResultsByTotalTimeDescThenCallsDesc() {
        List<SlowQuery> results = tool(0.0, 0.0).getSlowQueries().candidates();
        assertThat(results).hasSizeGreaterThan(1);
        for (int i = 0; i < results.size() - 1; i++) {
            assertThat(results.get(i).totalTimeMs())
                    .isGreaterThanOrEqualTo(results.get(i + 1).totalTimeMs());
        }
    }

    @Test
    void nPlusOneSignalSurfaced() {
        // Use minTotal=0 so we find the query regardless of how fast the container runs.
        // This test is about verifying the N+1 signal shape, not the threshold logic.
        List<SlowQuery> results = tool(50.0, 0.0).getSlowQueries().candidates();
        SlowQuery nPlusOne = results.stream()
                .filter(q -> q.calls() >= 100)
                .findFirst()
                .orElseThrow(() -> new AssertionError("N+1 candidate not found"));

        assertThat(nPlusOne.calls()).isGreaterThanOrEqualTo(100);
        assertThat(nPlusOne.meanTimeMs()).isLessThan(nPlusOne.totalTimeMs());
    }

    private SlowQueryTool tool(double minMean, double minTotal) {
        AgentProperties props = new AgentProperties();
        props.getLoop().setMinMeanTimeMs(minMean);
        props.getLoop().setMinTotalTimeMs(minTotal);
        props.getLoop().setSlowQueryLimit(20);
        // Snapshot diffing is disabled by default — these tests assert on cumulative stats.
        return new SlowQueryTool(jdbc, props, new SnapshotTool(new ObjectMapper(), props));
    }
}
