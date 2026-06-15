package dev.gyda.pgagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gyda.pgagent.config.AgentProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class AnthropicLlmClient implements LlmClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public AnthropicLlmClient(AgentProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        var cfg = props.getAnthropic();
        if (!StringUtils.hasText(cfg.getApiKey())) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is not set. Export it before running (see .env.example).");
        }

        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", cfg.getModel());
            body.put("max_tokens", cfg.getMaxTokens());
            body.put("system", systemPrompt);
            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.getBaseUrl()))
                    .timeout(Duration.ofSeconds(120))
                    .header("content-type", "application/json")
                    .header("x-api-key", cfg.getApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "Anthropic API returned HTTP " + response.statusCode() + ": " + response.body());
            }

            // Response shape: { content: [ { type: "text", text: "..." }, ... ] }
            JsonNode root = mapper.readTree(response.body());
            StringBuilder out = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    out.append(block.path("text").asText());
                }
            }
            return out.toString();

        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }
}
