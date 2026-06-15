package dev.gyda.pgagent.model;

/**
 * How well-supported a finding's classification is. Set by the LLM from how many independent
 * signals agree, then downgraded deterministically when a claimed signal isn't confirmed in
 * the real data (see AgentLoop's evidence cross-check).
 */
public enum Confidence {
    HIGH,
    MEDIUM,
    LOW
}
