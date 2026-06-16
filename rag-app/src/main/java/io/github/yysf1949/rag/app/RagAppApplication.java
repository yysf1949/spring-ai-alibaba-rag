package io.github.yysf1949.rag.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

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
 * <p>Design spec §13.12.</p>
 */
@SpringBootApplication(scanBasePackages = "io.github.yysf1949.rag")
@ConfigurationPropertiesScan("io.github.yysf1949.rag")
public class RagAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagAppApplication.class, args);
    }
}