package dev.gyda.pgagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test. Note: this starts the full context, which needs a reachable database and (because
 * AgentRunner runs on startup) may need an API key. When you add real logic, prefer slicing this
 * down or supplying a fake {@code LlmClient} and a test datasource so it runs offline.
 */
@SpringBootTest
class PgAgentApplicationTests {

    @Test
    void contextLoads() {
    }
}
