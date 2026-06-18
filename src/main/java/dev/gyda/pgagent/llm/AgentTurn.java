package dev.gyda.pgagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One assistant turn in a tool-use conversation.
 *
 * @param text            any prose the model emitted this turn
 * @param toolCalls       tools the model wants executed before it continues
 * @param assistantContent the raw content array to echo back into the message history
 * @param stopReason      Anthropic stop_reason ("tool_use", "end_turn", ...)
 */
public record AgentTurn(String text, List<ToolCall> toolCalls, JsonNode assistantContent, String stopReason) {
    public boolean wantsTools() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
