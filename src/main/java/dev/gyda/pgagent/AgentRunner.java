package dev.gyda.pgagent;

import dev.gyda.pgagent.agent.AgentLoop;
import dev.gyda.pgagent.model.AgentRunResult;
import dev.gyda.pgagent.model.Classification;
import dev.gyda.pgagent.model.Finding;
import dev.gyda.pgagent.model.HypoResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final AgentLoop agentLoop;
    private final EmailReporter emailReporter;

    public AgentRunner(AgentLoop agentLoop, EmailReporter emailReporter) {
        this.agentLoop = agentLoop;
        this.emailReporter = emailReporter;
    }

    @Override
    public void run(String... args) {
        log.info("=== Postgres Performance Agent ===");

        AgentRunResult result = agentLoop.run();
        List<Finding> findings = result.findings();

        if (findings.isEmpty()) {
            log.info("No findings.");
        } else {
            printGroup("FIX IN THE DATABASE", findings, Classification.DB_PROBLEM, Classification.MIXED);
            printGroup("FIX IN YOUR CODE",    findings, Classification.APP_PROBLEM, Classification.MIXED);
        }

        if (!result.resolvedQueries().isEmpty()) {
            log.info("--- RESOLVED SINCE LAST RUN ({}) ---", result.resolvedQueries().size());
            result.resolvedQueries().forEach(q -> log.info("  {}", abbreviate(q)));
        }

        emailReporter.send(result);
        log.info("Done.");
    }

    private static void printGroup(String header, List<Finding> findings, Classification... match) {
        List<Finding> group = findings.stream()
                .filter(f -> {
                    for (Classification c : match) {
                        if (f.classification() == c) return true;
                    }
                    return false;
                })
                .toList();
        if (group.isEmpty()) return;

        log.info("--- {} ({}) ---", header, group.size());
        for (Finding f : group) {
            log.info("  [{}] {}  calls={}  mean={} ms  total={} ms",
                    f.pathology(), abbreviate(f.query().queryText()),
                    f.query().calls(), fmt(f.query().meanTimeMs()), fmt(f.query().totalTimeMs()));
            log.info("    Root cause:   {}", f.rootCause());
            log.info("    Proposed fix: {}", f.proposedFix());
            log.info("    Evidence:     {}", f.evidence());
            log.info("    Verified:     {}", f.verified());
            if (f.hypoTest() != null) {
                HypoResult h = f.hypoTest();
                log.info("    Hypo test:    cost {} → {} (est. {}x)",
                        fmt(h.costBefore()), fmt(h.costAfter()), fmt(h.estimatedSpeedup()));
            }
            if (f.delta() != null) {
                log.info("    Measured:     {} ms → {} ms  (delta={} ms)",
                        fmt(f.beforeMs()), fmt(f.afterMs()), fmt(f.delta()));
            }
            log.info("");
        }
    }

    private static String fmt(double d) {
        return String.format("%.1f", d);
    }

    private static String abbreviate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 100 ? oneLine : oneLine.substring(0, 100) + "…";
    }
}
