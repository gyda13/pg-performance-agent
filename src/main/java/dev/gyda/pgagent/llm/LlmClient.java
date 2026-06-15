package dev.gyda.pgagent.llm;

public interface LlmClient {
    String complete(String systemPrompt, String userPrompt);
}
