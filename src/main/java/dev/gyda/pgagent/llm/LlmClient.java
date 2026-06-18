package dev.gyda.pgagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface LlmClient {

    /** Single text-in / text-out completion (used by the deterministic loop). */
    String complete(String systemPrompt, String userPrompt);

    /**
     * One round-trip of a tool-use conversation: send the running message history plus the
     * available tools, get back the assistant's turn (prose and/or tool calls). The caller owns
     * the message history and appends this turn's content plus any tool results before re-calling.
     * Default impl declines — only tool-use-capable clients (AnthropicLlmClient) override it.
     */
    default AgentTurn converse(String systemPrompt, List<ToolSpec> tools, List<JsonNode> messages) {
        throw new UnsupportedOperationException("This LlmClient does not support tool-use.");
    }
}
