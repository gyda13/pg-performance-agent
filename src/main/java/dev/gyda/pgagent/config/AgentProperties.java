package dev.gyda.pgagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "pgagent")
public class AgentProperties {

    @NestedConfigurationProperty
    private final Anthropic anthropic = new Anthropic();

    @NestedConfigurationProperty
    private final Loop loop = new Loop();

    @NestedConfigurationProperty
    private final Report report = new Report();

    @NestedConfigurationProperty
    private final Privacy privacy = new Privacy();

    @NestedConfigurationProperty
    private final Snapshot snapshot = new Snapshot();

    public Anthropic getAnthropic() { return anthropic; }
    public Loop getLoop() { return loop; }
    public Report getReport() { return report; }
    public Privacy getPrivacy() { return privacy; }
    public Snapshot getSnapshot() { return snapshot; }

    public static class Anthropic {
        private String apiKey = "";
        private String model = "claude-sonnet-4-6";
        private String baseUrl = "https://api.anthropic.com/v1/messages";
        private int maxTokens = 2048;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class Loop {
        private int maxIterations = 10;
        private int slowQueryLimit = 10;
        private long statementTimeoutMs = 5_000;
        private double minMeanTimeMs = 50.0;
        private double minTotalTimeMs = 500.0;
        private boolean applyFixes = false;
        private int benchmarkRuns = 5;
        private int benchLimit = 50;
        private long benchOffset = 100_000;
        private double minEstimatedSpeedup = 1.5;
        private double minMeasuredSpeedup = 1.1;
        private int maxRetriesPerQuery = 2;

        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        public int getSlowQueryLimit() { return slowQueryLimit; }
        public void setSlowQueryLimit(int slowQueryLimit) { this.slowQueryLimit = slowQueryLimit; }
        public long getStatementTimeoutMs() { return statementTimeoutMs; }
        public void setStatementTimeoutMs(long statementTimeoutMs) { this.statementTimeoutMs = statementTimeoutMs; }
        public double getMinMeanTimeMs() { return minMeanTimeMs; }
        public void setMinMeanTimeMs(double minMeanTimeMs) { this.minMeanTimeMs = minMeanTimeMs; }
        public double getMinTotalTimeMs() { return minTotalTimeMs; }
        public void setMinTotalTimeMs(double minTotalTimeMs) { this.minTotalTimeMs = minTotalTimeMs; }
        public boolean isApplyFixes() { return applyFixes; }
        public void setApplyFixes(boolean applyFixes) { this.applyFixes = applyFixes; }
        public int getBenchmarkRuns() { return benchmarkRuns; }
        public void setBenchmarkRuns(int benchmarkRuns) { this.benchmarkRuns = benchmarkRuns; }
        public int getBenchLimit() { return benchLimit; }
        public void setBenchLimit(int benchLimit) { this.benchLimit = benchLimit; }
        public long getBenchOffset() { return benchOffset; }
        public void setBenchOffset(long benchOffset) { this.benchOffset = benchOffset; }
        public double getMinEstimatedSpeedup() { return minEstimatedSpeedup; }
        public void setMinEstimatedSpeedup(double minEstimatedSpeedup) { this.minEstimatedSpeedup = minEstimatedSpeedup; }
        public double getMinMeasuredSpeedup() { return minMeasuredSpeedup; }
        public void setMinMeasuredSpeedup(double minMeasuredSpeedup) { this.minMeasuredSpeedup = minMeasuredSpeedup; }
        public int getMaxRetriesPerQuery() { return maxRetriesPerQuery; }
        public void setMaxRetriesPerQuery(int maxRetriesPerQuery) { this.maxRetriesPerQuery = maxRetriesPerQuery; }
    }

    public static class Report {
        private String recipientEmail = "";

        public String getRecipientEmail() { return recipientEmail; }
        public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }
    }

    public static class Privacy {
        // Opt-in: when true, real cell values (pg_stats most-common values and sampled
        // string literals in plans) are included in the evidence sent to the LLM. Improves
        // data-skew reasoning and enables value-specific partial-index recommendations.
        // Default false: only schema, plans, and metrics leave the agent's infrastructure.
        private boolean shareDataValues = false;

        public boolean isShareDataValues() { return shareDataValues; }
        public void setShareDataValues(boolean shareDataValues) { this.shareDataValues = shareDataValues; }
    }

    public static class Snapshot {
        // Time-windowed analysis: each run saves the cumulative counters to `path` and the
        // next run analyzes only the delta — "since last run" instead of "since stats reset".
        private boolean enabled = false;
        private String path = "pgagent-snapshot.json";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }
}
