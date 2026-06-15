package dev.gyda.pgagent.tools;

import dev.gyda.pgagent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces $N placeholders from pg_stat_statements with literal values sampled from
 * pg_stats (most common value, else histogram midpoint). NULL substitution short-circuits
 * plans and makes benchmark numbers meaningless; sampled real values keep the measured
 * evidence honest. When a parameter cannot be resolved, callers must fall back to
 * EXPLAIN (GENERIC_PLAN) and skip benchmarking — never guess.
 */
@Component
public class ParamResolver {

    private static final Logger log = LoggerFactory.getLogger(ParamResolver.class);

    private static final Pattern PARAM = Pattern.compile("\\$(\\d+)");
    private static final Pattern COLUMN_PARAM = Pattern.compile(
            "(?i)([a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)?)\\s*(?:=|<>|!=|<=|>=|<|>|LIKE|ILIKE)\\s*\\$(\\d+)");
    private static final Pattern LIMIT_PARAM = Pattern.compile("(?i)\\bLIMIT\\s+\\$(\\d+)");
    private static final Pattern OFFSET_PARAM = Pattern.compile("(?i)\\bOFFSET\\s+\\$(\\d+)");
    private static final Pattern TABLE_PATTERN =
            Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+(\\w+(?:\\.\\w+)?)");

    // Types whose pg_stats text representation is a valid bare SQL literal.
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "smallint", "integer", "bigint", "numeric", "real", "double precision");

    private final JdbcTemplate jdbc;
    private final AgentProperties props;

    public ParamResolver(JdbcTemplate jdbc, AgentProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** A runnable SQL string plus the literal substituted for each $N (for later redaction). */
    public record Resolution(String sql, Map<Integer, String> literals) {}

    /**
     * Returns the SQL with every $N replaced by a sampled literal, or empty if any
     * parameter cannot be resolved.
     */
    public Optional<Resolution> resolve(String sql) {
        Set<Integer> params = new TreeSet<>();
        Matcher pm = PARAM.matcher(sql);
        while (pm.find()) {
            params.add(Integer.parseInt(pm.group(1)));
        }
        if (params.isEmpty()) {
            return Optional.of(new Resolution(sql, Map.of()));
        }

        Map<Integer, String> literals = new HashMap<>();

        // LIMIT/OFFSET params have no column to sample from — use configured literals.
        Matcher lm = LIMIT_PARAM.matcher(sql);
        while (lm.find()) {
            literals.put(Integer.parseInt(lm.group(1)), String.valueOf(props.getLoop().getBenchLimit()));
        }
        Matcher om = OFFSET_PARAM.matcher(sql);
        while (om.find()) {
            literals.put(Integer.parseInt(om.group(1)), String.valueOf(props.getLoop().getBenchOffset()));
        }

        List<String> tables = extractTableNames(sql);
        Matcher cm = COLUMN_PARAM.matcher(sql);
        while (cm.find()) {
            int idx = Integer.parseInt(cm.group(2));
            if (literals.containsKey(idx)) {
                continue;
            }
            String column = lastSegment(cm.group(1));
            sampleLiteral(tables, column).ifPresent(v -> literals.put(idx, v));
        }

        if (!literals.keySet().containsAll(params)) {
            log.debug("Unresolved $N parameters, GENERIC_PLAN fallback: {}", sql);
            return Optional.empty();
        }

        String out = sql;
        for (Map.Entry<Integer, String> e : literals.entrySet()) {
            // (?!\d) so $1 does not also rewrite the prefix of $10.
            out = out.replaceAll("\\$" + e.getKey() + "(?!\\d)", Matcher.quoteReplacement(e.getValue()));
        }
        return Optional.of(new Resolution(out, literals));
    }

    // Sample a representative value for the column from whichever candidate table has it.
    private Optional<String> sampleLiteral(List<String> tables, String column) {
        for (String table : tables) {
            String schema = "public";
            String name = table;
            if (table.contains(".")) {
                String[] parts = table.split("\\.", 2);
                schema = parts[0];
                name = parts[1];
            }
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT (most_common_vals::text::text[])[1] AS mcv,
                           (histogram_bounds::text::text[])
                               [GREATEST(array_length(histogram_bounds::text::text[], 1) / 2, 1)] AS hist
                    FROM pg_stats
                    WHERE schemaname = ? AND tablename = ? AND attname = ?
                    """, schema, name, column);
            if (rows.isEmpty()) {
                continue;
            }
            Object raw = rows.get(0).get("mcv") != null ? rows.get(0).get("mcv") : rows.get(0).get("hist");
            if (raw == null) {
                continue;
            }
            return Optional.of(toLiteral(schema, name, column, raw.toString()));
        }
        return Optional.empty();
    }

    private String toLiteral(String schema, String table, String column, String value) {
        String dataType = null;
        try {
            dataType = jdbc.queryForObject("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = ? AND column_name = ?
                    """, String.class, schema, table, column);
        } catch (Exception ignored) {
            // Unknown type — quoting below is the safe representation.
        }
        if (dataType != null && NUMERIC_TYPES.contains(dataType)) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static String lastSegment(String identifier) {
        int dot = identifier.lastIndexOf('.');
        return (dot >= 0 ? identifier.substring(dot + 1) : identifier).toLowerCase();
    }

    public static List<String> extractTableNames(String sql) {
        List<String> tables = new ArrayList<>();
        Matcher m = TABLE_PATTERN.matcher(sql);
        while (m.find()) {
            tables.add(m.group(1).toLowerCase());
        }
        return tables.stream().distinct().toList();
    }
}
