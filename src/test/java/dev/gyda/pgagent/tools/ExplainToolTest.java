package dev.gyda.pgagent.tools;

import dev.gyda.pgagent.config.AgentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ExplainToolTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;        // production-equivalent: simple protocol for plain Statements
    static JdbcTemplate extendedJdbc; // reproduces the bug: default extended protocol

    @BeforeAll
    static void setup() {
        jdbc = template("extendedForPrepared");
        extendedJdbc = template("extended");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    id BIGSERIAL PRIMARY KEY,
                    customer_id BIGINT NOT NULL,
                    status TEXT NOT NULL
                )""");
        jdbc.execute("""
                INSERT INTO orders (customer_id, status)
                SELECT g % 1000, 'pending'
                FROM generate_series(1, 5000) AS g""");
    }

    private static JdbcTemplate template(String queryMode) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(pg.getJdbcUrl());
        ds.setUsername(pg.getUsername());
        ds.setPassword(pg.getPassword());
        Properties p = new Properties();
        // Mirrors spring.datasource.hikari.data-source-properties.preferQueryMode in application.yml.
        p.setProperty("preferQueryMode", queryMode);
        ds.setConnectionProperties(p);
        return new JdbcTemplate(ds);
    }

    @Test
    void genericPlanForQueryWithUnboundParam() {
        // The query still contains $1 — GENERIC_PLAN must plan it without client-binding the param.
        String plan = explainTool(jdbc).explain(
                "SELECT id, customer_id, status FROM orders WHERE id::text LIKE $1", false);

        assertThat(plan).contains("Plan").contains("orders");
    }

    @Test
    void extendedProtocolReproducesTheCrash() {
        // Without preferQueryMode=extendedForPrepared, the driver tries to bind $1 client-side
        // (0 params supplied for 1 required) and fails with SQLSTATE 08P01 — the original bug.
        assertThatThrownBy(() -> explainTool(extendedJdbc).explain(
                "SELECT id FROM orders WHERE id::text LIKE $1", false))
                .hasMessageContaining("bind message supplies 0 parameters");
    }

    @Test
    void analyzeRunsForResolvedSelect() {
        String plan = explainTool(jdbc).explain(
                "SELECT id FROM orders WHERE status = 'pending'", true);

        assertThat(plan).contains("Plan").contains("Actual"); // ANALYZE adds actual-time fields
    }

    @Test
    void refusesAnalyzeOnNonSelect() {
        assertThatThrownBy(() -> explainTool(jdbc).explain(
                "UPDATE orders SET status = 'x'", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesMultiStatement() {
        assertThatThrownBy(() -> explainTool(jdbc).explain(
                "SELECT 1; SELECT 2", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExplainTool explainTool(JdbcTemplate template) {
        AgentProperties props = new AgentProperties();
        props.getLoop().setStatementTimeoutMs(5000);
        return new ExplainTool(template, props);
    }
}
