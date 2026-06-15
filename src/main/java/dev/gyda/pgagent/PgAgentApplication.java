package dev.gyda.pgagent;

import dev.gyda.pgagent.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class PgAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PgAgentApplication.class, args);
    }
}
