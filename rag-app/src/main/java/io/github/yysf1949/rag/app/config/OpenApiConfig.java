package io.github.yysf1949.rag.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 / Swagger UI metadata — design spec §13.12.
 *
 * <p>Exposed by springdoc-openapi 2.6.0:
 * <ul>
 *   <li>{@code GET /v3/api-docs}        — machine-readable JSON</li>
 *   <li>{@code GET /v3/api-docs.yaml}   — machine-readable YAML</li>
 *   <li>{@code GET /swagger-ui.html}    — interactive UI</li>
 * </ul>
 *
 * <p>Bean is named {@code customOpenAPI} — the convention springdoc's
 * {@code OpenApiBeanFactoryConfiguration} looks for. Endpoints inherit
 * the {@code X-Tenant-Id} requirement from the global security scheme
 * below.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean(name = "customOpenAPI")
    public OpenAPI ragOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Spring AI Alibaba RAG API")
                        .version("0.1.0-SNAPSHOT")
                        .description("Multi-tenant RAG retrieval + answer-generation service. "
                                + "All /api/* endpoints require the X-Tenant-Id header. "
                                + "See docs/MULTI_TENANT.md for the tenant resolution rules.")
                        .contact(new Contact()
                                .name("RAG Team")
                                .email("rag@yysf1949.io"))
                        .license(new License()
                                .name("Apache-2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Tenant-Id")
                                .description("Tenant identifier propagated by the gateway. "
                                        + "Authoritative — the body field is ignored.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}