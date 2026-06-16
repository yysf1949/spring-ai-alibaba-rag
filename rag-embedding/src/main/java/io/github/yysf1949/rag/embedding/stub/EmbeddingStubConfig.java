package io.github.yysf1949.rag.embedding.stub;

import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.LlmService;
import io.github.yysf1949.rag.core.port.RerankService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the three stub adapters ({@link EmbeddingGateway},
 * {@link RerankService}, {@link LlmService}) when the real
 * DashScope-backed implementations are not on the classpath.
 *
 * <p>Phase 5-P4 will add a {@code DashScopeAutoConfiguration} that
 * supplies {@code @ConditionalOnMissingBean}-protected beans of the
 * same types. Because Spring evaluates {@code @ConditionalOnMissingBean}
 * by <b>type</b>, the real beans will simply replace these stubs when
 * present, and the stubs will only ever be wired in dev / unit / smoke
 * modes where the real impl is absent.</p>
 *
 * <p>This file is the boundary between "real DashScope land" and "stub
 * land" — it makes the production wiring path explicit and removes the
 * stubs from the Spring Boot entry point module ({@code rag-app}).</p>
 */
@Configuration
public class EmbeddingStubConfig {

    @Bean
    @ConditionalOnMissingBean(EmbeddingGateway.class)
    public EmbeddingGateway stubEmbeddingGateway() {
        return new StubEmbeddingGateway();
    }

    @Bean
    @ConditionalOnMissingBean(RerankService.class)
    public RerankService stubRerankService() {
        return new StubRerankService();
    }

    @Bean
    @ConditionalOnMissingBean(LlmService.class)
    public LlmService stubLlmService() {
        return new StubLlmService();
    }
}