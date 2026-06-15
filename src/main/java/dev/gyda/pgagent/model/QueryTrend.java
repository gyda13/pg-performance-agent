package dev.gyda.pgagent.model;

/** How a flagged query relates to the previous run's report. */
public enum QueryTrend {
    /** Above thresholds now, was not flagged in the previous run. */
    NEW,
    /** Was flagged in the previous run and is still above thresholds — not fixed yet. */
    RECURRING
}
