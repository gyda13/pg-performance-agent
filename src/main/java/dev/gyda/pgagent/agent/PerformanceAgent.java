package dev.gyda.pgagent.agent;

import dev.gyda.pgagent.model.AgentRunResult;

/** One full analysis pass. Implemented by the deterministic {@link AgentLoop} and the
 *  LLM-driven {@link AutonomousAgentLoop}; {@code AgentRunner} picks one per config. */
public interface PerformanceAgent {
    AgentRunResult run();
}
