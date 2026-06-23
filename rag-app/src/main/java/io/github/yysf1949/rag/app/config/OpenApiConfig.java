package io.github.yysf1949.rag.app.config;

import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata — now consolidated in agent's OpenApiConfig.
 *
 * <p>The agent module's {@code agentOpenApiConfig} provides the single
 * OpenAPI bean with full metadata for all endpoints (QA, Ingest, Agent,
 * Tool Catalog, KB/Document Versions). This class is kept as a placeholder
 * to avoid breaking any imports but no longer declares its own bean.</p>
 */
@Configuration
public class OpenApiConfig {
    // OpenAPI bean is now provided by io.github.yysf1949.rag.agent.web.OpenApiConfig
}
