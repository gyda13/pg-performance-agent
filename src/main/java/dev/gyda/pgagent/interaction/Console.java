package dev.gyda.pgagent.interaction;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Thin wrapper over stdin/stdout for interactive prompts. Reading is centralised here so the
 * loops stay testable: tests construct it with their own Reader. When no input is available
 * (e.g. the process has no console), readLine returns null and callers fall back to defaults.
 */
@Component
public class Console {

    private final BufferedReader reader;

    public Console() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    public Console(BufferedReader reader) {
        this.reader = reader;
    }

    public void print(String message) {
        System.out.println(message);
    }

    /** Prints the prompt and returns the entered line, or null if input is unavailable. */
    public String readLine(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        try {
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /** y/yes (case-insensitive) → true; anything else, or no input, → false. */
    public boolean confirm(String prompt) {
        String line = readLine(prompt);
        return line != null && line.strip().toLowerCase().startsWith("y");
    }
}
