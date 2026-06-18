package dev.gyda.pgagent.tools;

import dev.gyda.pgagent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ApplyTool {

    private static final Logger log = LoggerFactory.getLogger(ApplyTool.class);

    private static final Pattern INDEX_NAME = Pattern.compile(
            "(?i)^CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w\"$.]+)\\s+ON\\s");

    private final JdbcTemplate jdbc;
    private final AgentProperties props;

    public ApplyTool(JdbcTemplate jdbc, AgentProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public void apply(String ddlSql) {
        if (!props.getLoop().isApplyFixes()) {
            throw new UnsupportedOperationException(
                    "apply-fixes is disabled. Set pgagent.loop.apply-fixes=true and " +
                    "spring.datasource.hikari.read-only=false to enable Phase 3.");
        }

        String statement = ddlSql.strip().replaceAll(";\\s*$", "");
        String upper = statement.toUpperCase();
        if (!upper.startsWith("CREATE INDEX") && !upper.startsWith("CREATE UNIQUE INDEX")) {
            throw new IllegalArgumentException(
                    "ApplyTool only executes CREATE [UNIQUE] INDEX statements. Got: "
                    + abbreviate(statement));
        }

        // CONCURRENTLY so the build never takes a write-blocking lock on the table.
        // Note: CONCURRENTLY cannot run inside a transaction block — relies on autocommit.
        if (!upper.contains("CONCURRENTLY")) {
            statement = statement.replaceFirst("(?i)\\bINDEX\\b", "INDEX CONCURRENTLY");
        }

        log.warn("APPLYING DDL (ensure target is a replica or clone): {}", statement);
        try {
            jdbc.execute(statement);
            log.info("DDL applied successfully.");
        } catch (RuntimeException e) {
            // A failed CONCURRENTLY build leaves an INVALID index behind — clean it up.
            cleanUpAfterFailedBuild(statement);
            throw e;
        }
    }

    // STALE_STATS fix: refresh planner statistics for one table. ANALYZE is a stats-only write
    // (no data change), but it is still a write — gated by apply-fixes, and the identifier is
    // validated so nothing but a bare table name reaches the server.
    private static final Pattern TABLE_IDENT = Pattern.compile("(?i)^[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?$");

    public void analyze(String table) {
        if (!props.getLoop().isApplyFixes()) {
            throw new UnsupportedOperationException(
                    "apply-fixes is disabled. Set pgagent.loop.apply-fixes=true and " +
                    "spring.datasource.hikari.read-only=false to allow ANALYZE.");
        }
        String t = table.strip();
        if (!TABLE_IDENT.matcher(t).matches()) {
            throw new IllegalArgumentException("Refusing to ANALYZE — not a bare table identifier: " + t);
        }
        log.warn("APPLYING ANALYZE {} (stats-only write).", t);
        jdbc.execute("ANALYZE " + t);
        log.info("ANALYZE complete.");
    }

    // EVALUATE rollback path: an applied index whose measured speedup fails the bar is removed.
    public void drop(String createIndexSql) {
        if (!props.getLoop().isApplyFixes()) {
            throw new UnsupportedOperationException("apply-fixes is disabled.");
        }
        Optional<String> name = indexName(createIndexSql);
        if (name.isEmpty()) {
            log.warn("Could not derive an index name from '{}' — drop it manually.",
                    abbreviate(createIndexSql));
            return;
        }
        log.warn("DROPPING index {} — measured improvement below threshold.", name.get());
        jdbc.execute("DROP INDEX CONCURRENTLY IF EXISTS " + name.get());
        log.info("Index dropped.");
    }

    private void cleanUpAfterFailedBuild(String statement) {
        indexName(statement).ifPresent(name -> {
            try {
                jdbc.execute("DROP INDEX CONCURRENTLY IF EXISTS " + name);
                log.warn("Cleaned up index {} after failed CONCURRENTLY build.", name);
            } catch (Exception cleanup) {
                log.warn("Could not clean up index {} after failed build: {}", name, cleanup.getMessage());
            }
        });
    }

    private static Optional<String> indexName(String createIndexSql) {
        Matcher m = INDEX_NAME.matcher(createIndexSql.strip());
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
