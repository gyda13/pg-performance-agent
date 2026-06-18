package dev.gyda.pgagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gyda.pgagent.config.AgentProperties;
import dev.gyda.pgagent.interaction.ApplyApproval;
import dev.gyda.pgagent.interaction.Console;
import dev.gyda.pgagent.llm.AgentTurn;
import dev.gyda.pgagent.llm.LlmClient;
import dev.gyda.pgagent.llm.ToolCall;
import dev.gyda.pgagent.llm.ToolSpec;
import dev.gyda.pgagent.model.BenchmarkResult;
import dev.gyda.pgagent.model.Classification;
import dev.gyda.pgagent.model.Finding;
import dev.gyda.pgagent.model.HypoResult;
import dev.gyda.pgagent.model.PerceptionResult;
import dev.gyda.pgagent.model.SlowQuery;
import dev.gyda.pgagent.tools.ApplyTool;
import dev.gyda.pgagent.tools.BenchmarkTool;
import dev.gyda.pgagent.tools.ExplainTool;
import dev.gyda.pgagent.tools.HypoPGTool;
import dev.gyda.pgagent.tools.ParamResolver;
import dev.gyda.pgagent.tools.RelatedQueryTool;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AutonomousAgentLoopTest {

    @Mock SlowQueryTool slowQueryTool;
    @Mock ExplainTool explainTool;
    @Mock TableInspectionTool tableInspectionTool;
    @Mock ParamResolver paramResolver;
    @Mock HypoPGTool hypoPGTool;
    @Mock BenchmarkTool benchmarkTool;
    @Mock ApplyTool applyTool;
    @Mock RelatedQueryTool relatedQueryTool;
    @Mock LlmClient llm;

    final ObjectMapper mapper = new ObjectMapper();
    AutonomousAgentLoop loop;
    AgentProperties props;

    static final SlowQuery OFFSET_QUERY = new SlowQuery(
            "SELECT id, status FROM orders ORDER BY created_at LIMIT $1 OFFSET $2",
            60L, 7000.0, 116.0, 1200L);

    @BeforeEach
    void setUp() {
        props = new AgentProperties();
        when(slowQueryTool.getSlowQueries()).thenReturn(
                new PerceptionResult(List.of(OFFSET_QUERY), Map.of(), List.of(), null));
        // Auto-approve DB writes in tests by feeding "y" to the approval prompt.
        ApplyApproval approval = new ApplyApproval(
                new Console(new java.io.BufferedReader(new java.io.StringReader("y\n".repeat(10)))));
        loop = new AutonomousAgentLoop(slowQueryTool, explainTool, tableInspectionTool, paramResolver,
                hypoPGTool, benchmarkTool, applyTool, relatedQueryTool, llm, mapper, props, approval);
    }

    private AgentTurn turn(ToolCall... calls) {
        return new AgentTurn("", List.of(calls), mapper.createArrayNode(), "tool_use");
    }

    private ToolCall call(String name, Map<String, String> args) {
        ObjectNode in = mapper.createObjectNode();
        args.forEach(in::put);
        return new ToolCall("id-" + name, name, in);
    }

    private ToolCall submit(String classification, String pathology) {
        ObjectNode in = mapper.createObjectNode();
        ArrayNode findings = in.putArray("findings");
        ObjectNode f = findings.addObject();
        f.put("query", OFFSET_QUERY.queryText());
        f.put("classification", classification);
        f.put("pathology", pathology);
        f.put("confidence", "HIGH");
        f.put("evidence", "e");
        f.put("root_cause", "rc");
        f.put("proposed_fix", "CREATE INDEX orders_created_at_id ON orders (created_at, id)");
        f.put("tradeoffs", "t");
        return new ToolCall("id-submit", "submit_findings", in);
    }

    @Test
    void measuredNumbersComeFromToolsNotTheLlm() {
        props.getLoop().setApplyFixes(true);
        when(paramResolver.resolve(anyString())).thenReturn(
                Optional.of(new ParamResolver.Resolution("SELECT id, status FROM orders ORDER BY created_at LIMIT 50 OFFSET 100000", Map.of())));
        when(hypoPGTool.test(anyString(), anyString())).thenReturn(
                new HypoResult("orders_created_at_id", 1000.0, 50.0, 20.0));
        // before, then after
        when(benchmarkTool.benchmark(anyString(), anyInt())).thenReturn(
                new BenchmarkResult(5, 180.0, 220.0, 200.0),
                new BenchmarkResult(5, 9.0, 11.0, 10.0));

        when(llm.converse(anyString(), any(), any())).thenReturn(
                turn(call("test_hypothetical_index", Map.of(
                        "query", OFFSET_QUERY.queryText(),
                        "create_index_sql", "CREATE INDEX orders_created_at_id ON orders (created_at, id)"))),
                turn(call("benchmark_and_apply_index", Map.of(
                        "query", OFFSET_QUERY.queryText(),
                        "create_index_sql", "CREATE INDEX orders_created_at_id ON orders (created_at, id)"))),
                turn(submit("DB_PROBLEM", "DEEP_OFFSET")));

        List<Finding> findings = loop.run().findings();

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.classification()).isEqualTo(Classification.DB_PROBLEM);
        // Numbers are the ones the tools returned, attached by Java — never produced by the LLM.
        assertThat(f.verified()).isTrue();
        assertThat(f.beforeMs()).isEqualTo(200.0);
        assertThat(f.afterMs()).isEqualTo(10.0);
        assertThat(f.delta()).isEqualTo(190.0);
        assertThat(f.hypoTest().estimatedSpeedup()).isEqualTo(20.0);
        // The candidate stats are preserved on the finding (matched by query text).
        assertThat(f.query().calls()).isEqualTo(60L);
        verify(applyTool).apply(anyString());
        verify(applyTool, never()).drop(anyString());
    }

    @Test
    void appProblemCarriesNoMeasurementEvenIfToolsRan() {
        // Model submits an APP_PROBLEM finding; rule #2 forces verified=false and no numbers.
        when(llm.converse(anyString(), any(), any())).thenReturn(
                turn(submit("APP_PROBLEM", "DEEP_OFFSET")));

        List<Finding> findings = loop.run().findings();

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.classification()).isEqualTo(Classification.APP_PROBLEM);
        assertThat(f.verified()).isFalse();
        assertThat(f.hypoTest()).isNull();
        assertThat(f.beforeMs()).isNull();
    }

    @Test
    void applyIsRefusedWhenApplyFixesDisabled() {
        props.getLoop().setApplyFixes(false);
        when(llm.converse(anyString(), any(), any())).thenReturn(
                turn(call("benchmark_and_apply_index", Map.of(
                        "query", OFFSET_QUERY.queryText(),
                        "create_index_sql", "CREATE INDEX x ON orders (created_at)"))),
                turn(submit("DB_PROBLEM", "DEEP_OFFSET")));

        List<Finding> findings = loop.run().findings();

        assertThat(findings).hasSize(1);
        // benchmark/apply never ran because the tool short-circuits on read-only.
        verify(benchmarkTool, never()).benchmark(anyString(), anyInt());
        verify(applyTool, never()).apply(anyString());
        assertThat(findings.get(0).verified()).isFalse();
    }

    @Test
    void declinedApprovalLeavesDbUnchanged() {
        props.getLoop().setApplyFixes(true);
        when(paramResolver.resolve(anyString())).thenReturn(
                Optional.of(new ParamResolver.Resolution("SELECT 1", Map.of())));
        // A console that answers "n" to the approval prompt.
        ApplyApproval deny = new ApplyApproval(
                new Console(new java.io.BufferedReader(new java.io.StringReader("n\n"))));
        AutonomousAgentLoop denyLoop = new AutonomousAgentLoop(slowQueryTool, explainTool,
                tableInspectionTool, paramResolver, hypoPGTool, benchmarkTool, applyTool,
                relatedQueryTool, llm, mapper, props, deny);

        when(llm.converse(anyString(), any(), any())).thenReturn(
                turn(call("benchmark_and_apply_index", Map.of(
                        "query", OFFSET_QUERY.queryText(),
                        "create_index_sql", "CREATE INDEX x ON orders (created_at)"))),
                turn(submit("DB_PROBLEM", "DEEP_OFFSET")));

        List<Finding> findings = denyLoop.run().findings();

        verify(applyTool, never()).apply(anyString());
        verify(benchmarkTool, never()).benchmark(anyString(), anyInt());
        assertThat(findings.get(0).verified()).isFalse();
    }

    @Test
    void dispatchesNewReadOnlyTool() {
        when(relatedQueryTool.findRelated(anyString())).thenReturn("calls=2000 ... parent found");
        when(llm.converse(anyString(), any(), any())).thenReturn(
                turn(call("find_related_queries", Map.of("pattern", "customers"))),
                turn(submit("APP_PROBLEM", "N_PLUS_ONE")));

        loop.run();

        verify(relatedQueryTool).findRelated(anyString());
    }

    @Test
    void haltsAtMaxToolCallsWhenModelNeverSubmits() {
        props.getLoop().setMaxToolCalls(3);
        // Model keeps calling a tool forever, never submits.
        when(llm.converse(anyString(), any(), any())).thenReturn(
                turn(call("inspect_table", Map.of("table", "orders"))));
        when(tableInspectionTool.inspect(anyString())).thenReturn(
                new dev.gyda.pgagent.model.TableStats("orders", 2000000L, 1024L, List.of(), List.of(), null));

        List<Finding> findings = loop.run().findings();

        assertThat(findings).isEmpty();   // never submitted, but the loop still halted
        verify(llm, org.mockito.Mockito.times(3)).converse(anyString(), any(), any());
    }
}
