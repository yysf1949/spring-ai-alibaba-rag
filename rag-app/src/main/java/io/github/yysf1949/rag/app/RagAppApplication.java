package io.github.yysf1949.rag.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * <p>Wires the rag-core domain, rag-embedding DashScope gateway, rag-redis
 * vector + caches, and rag-pipeline orchestrator together.</p>
 *
 * <p>Full HTTP controllers land in Phase 6 — for now this class is the
 * bootable Spring Boot application that the
 * {@code spring-boot-maven-plugin} repackage step needs as the main class.</p>
 *
 * <h2>Auto-configuration excluded</h2>
 * <ul>
 *   <li>{@link RedisAutoConfiguration} — we want explicit control. The
 *       real rag-redis beans are wired by us, not auto-configured from
 *       "Redis is on the classpath". Spring Boot's default would try
 *       {@code localhost:6379} at startup and break the stub-only dev mode.</li>
 *   <li>{@code DashScopeAgentAutoConfiguration} + friends — the real
 *       DashScope client wants an API key at construction time. Until
 *       the key is configured (Phase 5-P4) we don't want any of its
 *       beans to be created. The placeholder DashScope beans are wired
 *       explicitly in {@code BeansConfig} when needed.</li>
 * </ul>
 *
 * <p>Design spec §13.12.</p>
 */
@SpringBootApplication(scanBasePackages = "io.github.yysf1949.rag",
                       exclude = {
                               RedisAutoConfiguration.class,
                               RedisRepositoriesAutoConfiguration.class
                       })
@ConfigurationPropertiesScan("io.github.yysf1949.rag")
@EnableScheduling // Phase 39 / R14 — ExperimentAutoWinnerRunner
public class RagAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagAppApplication.class, args);
    }
}