package io.github.yysf1949.rag.embedding.siliconflow;

import io.github.yysf1949.rag.core.port.EmbeddingCache;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.LlmService;
import io.github.yysf1949.rag.core.port.RerankService;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Wires the SiliconFlow adapters (Phase 5-P4).
 *
 * <p>Activated <b>only</b> when both conditions hold:
 * <ol>
 *   <li>{@code rag.siliconflow.enabled=true}</li>
 *   <li>{@code rag.siliconflow.api-key} is non-blank (i.e. SILICONFLOW_API_KEY env var set)</li>
 * </ol>
 * See {@link SiliconFlowProperties#isActive()}.</p>
 *
 * <p>When inactive, the stub adapters in
 * {@code io.github.yysf1949.rag.embedding.stub.EmbeddingStubConfig}
 * remain wired — so dev / test / no-key environments continue to work.</p>
 */
@Configuration
@Conditional(SiliconFlowAutoConfiguration.SiliconFlowActiveCondition.class)
@EnableConfigurationProperties(SiliconFlowProperties.class)
public class SiliconFlowAutoConfiguration {

    /**
     * SpEL-based condition: gates the entire config on the active flag,
     * so we never construct half-wired beans.
     */
    static class SiliconFlowActiveCondition
            implements org.springframework.context.annotation.Condition {

        @Override
        public boolean matches(
                org.springframework.context.annotation.ConditionContext context,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            // SPRING_APPLICATION_JSON injects resolved values directly
            // into the Environment as first-class PropertySource,
            // bypassing the YAML-placeholder-unresolved issue.
            boolean enabled = "true".equalsIgnoreCase(env.getProperty("rag.siliconflow.enabled", "false"));
            String key = env.getProperty("rag.siliconflow.api-key", "");
            return enabled && !key.isBlank();
        }
    }

    @Bean
    public WebClient siliconFlowWebClient(SiliconFlowProperties props) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(120, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(60, TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)) // 8MB for LLM responses
                .build();
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public EmbeddingGateway siliconFlowEmbeddingGateway(
            WebClient siliconFlowWebClient,
            SiliconFlowProperties props,
            ObjectProvider<EmbeddingCache> cacheProvider,
            io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry circuitBreakerRegistry) {
        EmbeddingCache cache = cacheProvider.getIfAvailable();
        return new SiliconFlowEmbeddingGateway(
                siliconFlowWebClient, props, cache,
                Duration.ofSeconds(1), Duration.ofSeconds(5),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                circuitBreakerRegistry);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public RerankService siliconFlowRerankService(
            WebClient siliconFlowWebClient,
            SiliconFlowProperties props) {
        return new SiliconFlowRerankService(siliconFlowWebClient, props);
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public LlmService siliconFlowLlmService(
            WebClient siliconFlowWebClient,
            SiliconFlowProperties props) {
        return new SiliconFlowLlmService(siliconFlowWebClient, props);
    }
}