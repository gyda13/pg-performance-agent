package dev.gyda.pgagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** A tool the model may call: name, human description, and a JSON-schema for its input. */
public record ToolSpec(String name, String description, JsonNode inputSchema) {}
