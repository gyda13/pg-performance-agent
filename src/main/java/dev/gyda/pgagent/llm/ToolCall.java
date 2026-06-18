package dev.gyda.pgagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** A tool invocation the model requested: its id (for the matching result), name, and input args. */
public record ToolCall(String id, String name, JsonNode input) {}
