package dev.gyda.pgagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gyda.pgagent.config.AgentProperties;
import dev.gyda.pgagent.model.StatsSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists a point-in-time copy of the pg_stat_statements counters to a local JSON file so
 * the next run can diff against it and analyze only the activity in between. Deliberately
 * client-side: the agent stays read-only, and the server counters are left untouched for
 * any other consumer (dashboards, pganalyze, ops scripts).
 */
@Component
public class SnapshotTool {

    private static final Logger log = LoggerFactory.getLogger(SnapshotTool.class);

    private final ObjectMapper mapper;
    private final AgentProperties props;

    public SnapshotTool(ObjectMapper mapper, AgentProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    public Optional<StatsSnapshot> load() {
        if (!props.getSnapshot().isEnabled()) {
            return Optional.empty();
        }
        Path path = Path.of(props.getSnapshot().getPath());
        if (!Files.exists(path)) {
            log.info("No snapshot at {} — this run analyzes the full pg_stat_statements history.", path);
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(path.toFile(), StatsSnapshot.class));
        } catch (IOException e) {
            log.warn("Snapshot at {} unreadable ({}) — analyzing full history.", path, e.getMessage());
            return Optional.empty();
        }
    }

    public void save(Map<String, StatsSnapshot.Counters> queries, List<String> reportedQueries) {
        if (!props.getSnapshot().isEnabled()) {
            return;
        }
        Path path = Path.of(props.getSnapshot().getPath());
        try {
            mapper.writeValue(path.toFile(),
                    new StatsSnapshot(System.currentTimeMillis(), queries, reportedQueries));
            log.info("Saved counters for {} queries to {} — the next run analyzes from this point.",
                    queries.size(), path);
        } catch (IOException e) {
            log.warn("Could not save snapshot to {}: {}", path, e.getMessage());
        }
    }
}
