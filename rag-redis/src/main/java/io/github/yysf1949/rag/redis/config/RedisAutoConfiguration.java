package io.github.yysf1949.rag.redis.config;

import io.github.yysf1949.rag.core.port.AnswerCache;
import io.github.yysf1949.rag.core.port.EmbeddingCache;
import io.github.yysf1949.rag.core.port.RewriteCache;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.redis.cache.RedisAnswerCache;
import io.github.yysf1949.rag.redis.cache.RedisEmbeddingCache;
import io.github.yysf1949.rag.redis.cache.RedisRewriteCache;
import io.github.yysf1949.rag.redis.vector.RedisIndexManager;
import io.github.yysf1949.rag.redis.vector.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the rag-redis adapters into the Spring application context.
 *
 * <p>Activated when (a) {@code spring.rag.redis.enabled} is {@code true}
 * (default) and (b) the {@code JedisPooled} class is on the classpath
 * (i.e. rag-redis itself is being used). In unit / dev-stub modes the
 * property can be set to {@code false} to suppress the wiring and let
 * the {@code Noop} fallbacks in {@code BeansConfig} take over.</p>
 *
 * <p>Each {@link Bean} is guarded by {@link ConditionalOnMissingBean}
 * so individual test overrides (e.g. a pre-seeded {@link VectorStore}
 * for a smoke test) win.</p>
 *
 * <h2>What this does NOT provide</h2>
 * <ul>
 *   <li>{@code IngestJobRepository} — no Redis impl yet; the
 *       in-memory implementation in rag-pipeline is used.</li>
 *   <li>{@code HotQuestionProvider} — no Redis impl yet; the
 *       in-memory list in rag-app is used.</li>
 *   <li>{@code EmbeddingGateway / RerankService / LlmService} —
 *       provided by rag-embedding (DashScope or stub).</li>
 * </ul>
 */
@Configuration
@ConditionalOnClass(name = "redis.clients.jedis.JedisPooled")
@ConditionalOnProperty(prefix = "spring.rag.redis", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RedisProperties.class)
public class RedisAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public RedisConnection redisConnection(RedisProperties properties) {
        return new RedisConnection(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisIndexManager redisIndexManager(
            RedisConnection connection,
            @Value("${spring.rag.embedding.dim:1024}") int dimension) {
        // Match the dim used by RedisEmbeddingCache and EmbeddingGateway.
        // Default = 1024 (SiliconFlow BAAI/bge-m3, Phase 5-P4).
        // Override via spring.rag.embedding.dim if you swap models.
        return new RedisIndexManager(
                connection, dimension,
                RedisIndexManager.DEFAULT_M,
                RedisIndexManager.DEFAULT_EF_CONSTRUCTION,
                RedisIndexManager.DEFAULT_EF_RUNTIME);
    }

    @Bean
    @ConditionalOnMissingBean
    public VectorStore redisVectorStore(RedisConnection connection,
                                        RedisIndexManager indexManager) {
        return new RedisVectorStore(connection, indexManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public AnswerCache redisAnswerCache(RedisConnection connection) {
        // 24-hour TTL matches the answer-cache default in spec §9.1
        return new RedisAnswerCache(connection, java.time.Duration.ofHours(24).toSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingCache redisEmbeddingCache(
            RedisConnection connection,
            @Value("${spring.rag.embedding.dim:1024}") int dimension) {
        // Embedding dim must match the gateway's. Default = 1024
        // (SiliconFlow BAAI/bge-m3, Phase 5-P4). Override via
        // spring.rag.embedding.dim to swap models.
        // 7-day TTL: embeddings are stable forever per spec §9.1.
        return new RedisEmbeddingCache(connection, dimension,
                java.time.Duration.ofDays(7).toSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    public RewriteCache redisRewriteCache(RedisConnection connection) {
        // 10-minute TTL: query rewrites are session-scoped and should
        // not leak across tenant boundaries (spec §11.2).
        return new RedisRewriteCache(connection,
                java.time.Duration.ofMinutes(10).toSeconds());
    }
}