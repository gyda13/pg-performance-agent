package dev.gyda.pgagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gyda.pgagent.config.AgentProperties;
import dev.gyda.pgagent.interaction.ApplyApproval;
import dev.gyda.pgagent.interaction.Console;
import dev.gyda.pgagent.llm.LlmClient;
import dev.gyda.pgagent.model.BenchmarkResult;
import dev.gyda.pgagent.model.Classification;
import dev.gyda.pgagent.model.Finding;
import dev.gyda.pgagent.model.HypoResult;
import dev.gyda.pgagent.model.Pathology;
import dev.gyda.pgagent.model.PerceptionResult;
import dev.gyda.pgagent.model.SlowQuery;
import dev.gyda.pgagent.model.TableStats;
import dev.gyda.pgagent.tools.ApplyTool;
import dev.gyda.pgagent.tools.BenchmarkTool;
import dev.gyda.pgagent.tools.ExplainTool;
import dev.gyda.pgagent.tools.HypoPGTool;
import dev.gyda.pgagent.tools.ParamResolver;
import dev.gyda.pgagent.tools.SlowQueryTool;
import dev.gyda.pgagent.tools.TableInspectionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentLoopTest {

    @Mock SlowQueryTool slowQueryTool;
    @Mock ExplainTool explainTool;
    @Mock TableInspectionTool tableInspectionTool;
    @Mock ParamResolver paramResolver;
    @Mock HypoPGTool hypoPGTool;
    @Mock BenchmarkTool benchmarkTool;
    @Mock ApplyTool applyTool;
    @Mock LlmClient llm;

    AgentLoop loop;
    AgentProperties props;

    static final String SIMPLE_PLAN = "[{\"Plan\":{\"Node Type\":\"Seq Scan\",\"Total Cost\":1000.0,\"Actual Rows\":10000}}]";
    static final SlowQuery SLOW_QUERY = new SlowQuery(
            "SELECT id, email FROM customers WHERE id = $1",
            2000L, 500.0, 0.25, 2000L);

    @BeforeEach
    void setUp() throws Exception {
        props = new AgentProperties();
        props.getLoop().setApplyFixes(false);

        when(slowQueryTool.getSlowQueries()).thenReturn(
                new PerceptionResult(List.of(SLOW_QUERY), Map.of(), List.of(), null));
        when(explainTool.explain(anyString(), anyBoolean())).thenReturn(SIMPLE_PLAN);
        when(tableInspectionTool.inspect(anyString())).thenReturn(
                new TableStats("customers", 100000L, 8192000L, List.of(), List.of(), null));
        when(paramResolver.resolve(anyString())).thenReturn(
                Optional.of(new ParamResolver.Resolution(
                        "SELECT id, email FROM customers WHERE id = 42", Map.of())));

        // Auto-approve DB writes in tests by feeding "y" to the approval prompt.
        ApplyApproval approval = new ApplyApproval(
                new Console(new java.io.BufferedReader(new java.io.StringReader("y\n".repeat(10)))));
        loop = new AgentLoop(slowQueryTool, explainTool, tableInspectionTool, paramResolver,
                hypoPGTool, benchmarkTool, applyTool, llm, new ObjectMapper(), props, approval);
    }

    @Test
    void appProblemFindingHasVerifiedFalseAndSkipsPhases23() throws Exception {
        when(llm.complete(anyString(), anyString())).thenReturn("""
                {
                  "classification": "APP_PROBLEM",
                  "pathology": "N_PLUS_ONE",
                  "evidence": "calls=2000, mean=0.25ms, total=500ms, single-row FK lookup",
                  "root_cause": "ORM is issuing one query per entity",
                  "proposed_fix": "Use JOIN FETCH or @BatchSize to load the collection in one query",
                  "tradeoffs": "requires ORM config change, no DB modification"
                }
                """);

        List<Finding> findings = loop.run().findings();

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.classification()).isEqualTo(Classification.APP_PROBLEM);
        assertThat(f.pathology()).isEqualTo(Pathology.N_PLUS_ONE);
        assertThat(f.verified()).isFalse();
        assertThat(f.hypoTest()).isNull();
        assertThat(f.beforeMs()).isNull();
        assertThat(f.afterMs()).isNull();
        assertThat(f.delta()).isNull();

        verify(hypoPGTool, never()).test(anyString(), anyString());
        verify(benchmarkTool, never()).benchmark(anyString(), anyInt());
        verify(applyTool, never()).apply(anyString());
    }

    @Test
    void dbProblemFindingAttemptsPhase2WhenIndexProposed() throws Exception {
        when(llm.complete(anyString(), anyString())).thenReturn("""
                {
                  "classification": "DB_PROBLEM",
                  "pathology": "MISSING_INDEX",
                  "evidence": "Seq Scan on customers, rows=100000, filter on id",
                  "root_cause": "Missing index on customers(id) for FK lookups",
                  "proposed_fix": "CREATE INDEX idx_customers_id ON customers(id)",
                  "tradeoffs": "slight write overhead, 8KB storage"
                }
                """);

        when(hypoPGTool.test(anyString(), anyString()))
                .thenReturn(new HypoResult("CREATE INDEX idx_customers_id ON customers(id)", 1000.0, 10.0, 100.0));

        List<Finding> findings = loop.run().findings();

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.classification()).isEqualTo(Classification.DB_PROBLEM);
        assertThat(f.pathology()).isEqualTo(Pathology.MISSING_INDEX);
        assertThat(f.hypoTest()).isNotNull();
        assertThat(f.hypoTest().estimatedSpeedup()).isEqualTo(100.0);
        assertThat(f.verified()).isFalse(); // apply-fixes is false

        verify(hypoPGTool).test(anyString(), anyString());
        verify(benchmarkTool, never()).benchmark(anyString(), anyInt());
    }

    @Test
    void dbProblemWithApplyFixesRunsPhase3() throws Exception {
        props.getLoop().setApplyFixes(true);
        props.getLoop().setBenchmarkRuns(3);

        when(llm.complete(anyString(), anyString())).thenReturn("""
                {
                  "classification": "DB_PROBLEM",
                  "pathology": "MISSING_INDEX",
                  "evidence": "Seq Scan, rows=100000",
                  "root_cause": "No index on customers.id",
                  "proposed_fix": "CREATE INDEX idx_c_id ON customers(id)",
                  "tradeoffs": "write overhead"
                }
                """);

        when(hypoPGTool.test(anyString(), anyString()))
                .thenReturn(new HypoResult("CREATE INDEX idx_c_id ON customers(id)", 1000.0, 10.0, 100.0));
        when(benchmarkTool.benchmark(anyString(), anyInt()))
                .thenReturn(new BenchmarkResult(3, 45.0, 55.0, 50.0))
                .thenReturn(new BenchmarkResult(3, 4.0, 6.0, 5.0));

        List<Finding> findings = loop.run().findings();

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.verified()).isTrue();
        assertThat(f.beforeMs()).isEqualTo(50.0);
        assertThat(f.afterMs()).isEqualTo(5.0);
        assertThat(f.delta()).isEqualTo(45.0);

        verify(applyTool).apply(anyString());
    }

    @Test
    void extensionDependentFixIsNotAppliedAndStaysUnverified() throws Exception {
        // The pg_trgm/GIN case: the fix needs CREATE EXTENSION, which ApplyTool can't run. Phase 3
        // must be skipped entirely (no apply, no approval prompt), not attempted and then rejected.
        props.getLoop().setApplyFixes(true);

        when(llm.complete(anyString(), anyString())).thenReturn("""
                {
                  "classification": "DB_PROBLEM",
                  "pathology": "LEADING_WILDCARD",
                  "evidence": "Seq Scan on customers, email LIKE",
                  "root_cause": "No usable index for the LIKE pattern",
                  "proposed_fix": "CREATE EXTENSION IF NOT EXISTS pg_trgm; CREATE INDEX idx_email_trgm ON customers USING gin (email gin_trgm_ops)",
                  "tradeoffs": "GIN index size"
                }
                """);

        List<Finding> findings = loop.run().findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).verified()).isFalse();
        verify(applyTool, never()).apply(anyString());
        verify(benchmarkTool, never()).benchmark(anyString(), anyInt());
    }

    @Test
    void createIndexEmbeddedInProseIsExtractedAndApplied() throws Exception {
        props.getLoop().setApplyFixes(true);
        props.getLoop().setBenchmarkRuns(3);

        when(llm.complete(anyString(), anyString())).thenReturn("""
                {
                  "classification": "DB_PROBLEM",
                  "pathology": "MISSING_INDEX",
                  "evidence": "Seq Scan, rows=100000",
                  "root_cause": "No index on customers.id",
                  "proposed_fix": "DB layer: create the index CREATE INDEX idx_c_id ON customers(id); then retest the query",
                  "tradeoffs": "write overhead"
                }
                """);
        when(hypoPGTool.test(anyString(), anyString()))
                .thenReturn(new HypoResult("CREATE INDEX idx_c_id ON customers(id)", 1000.0, 10.0, 100.0));
        when(benchmarkTool.benchmark(anyString(), anyInt()))
                .thenReturn(new BenchmarkResult(3, 45.0, 55.0, 50.0))
                .thenReturn(new BenchmarkResult(3, 4.0, 6.0, 5.0));

        List<Finding> findings = loop.run().findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).verified()).isTrue();
        // The index was isolated from the surrounding prose (no trailing "; then retest…").
        verify(applyTool).apply("CREATE INDEX idx_c_id ON customers(id)");
    }

    @Test
    void malformedLlmResponseIsDiscardedGracefully() throws Exception {
        when(llm.complete(anyString(), anyString())).thenReturn("Sorry, I cannot help with that.");

        List<Finding> findings = loop.run().findings();

        assertThat(findings).isEmpty();
    }

    @Test
    void markdownFencedJsonIsParsedCorrectly() throws Exception {
        when(llm.complete(anyString(), anyString())).thenReturn("""
                ```json
                {
                  "classification": "APP_PROBLEM",
                  "pathology": "LEADING_WILDCARD",
                  "evidence": "LIKE '%@example.com', Seq Scan despite email index",
                  "root_cause": "Leading wildcard prevents btree index use",
                  "proposed_fix": "Switch to full-text search or pg_trgm index",
                  "tradeoffs": "requires application query change"
                }
                ```""");

        List<Finding> findings = loop.run().findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).pathology()).isEqualTo(Pathology.LEADING_WILDCARD);
    }

    @Test
    void maxIterationsHaltsLoop() throws Exception {
        props.getLoop().setMaxIterations(0);
        when(llm.complete(anyString(), anyString())).thenReturn("{}");

        List<Finding> findings = loop.run().findings();

        assertThat(findings).isEmpty();
        verify(llm, never()).complete(anyString(), anyString());
    }
}
