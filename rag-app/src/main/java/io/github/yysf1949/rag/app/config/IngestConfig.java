package io.github.yysf1949.rag.app.config;

import io.github.yysf1949.rag.core.port.IngestJobRepository;
import io.github.yysf1949.rag.core.port.IngestService;
import io.github.yysf1949.rag.pipeline.ingest.IngestJobRepositoryImpl;
import io.github.yysf1949.rag.pipeline.ingest.IngestServiceImpl;
import io.github.yysf1949.rag.pipeline.splitter.ChunkSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the ingest pipeline (ChunkSplitter + IngestService + job repo).
 *
 * <p>The async executor is a dedicated daemon pool (spec §6.3) — never on
 * the web container's request threads, never on the JVM shutdown blocker.</p>
 */
@Configuration
public class IngestConfig {

    private static final Logger log = LoggerFactory.getLogger(IngestConfig.class);

    @Bean
    public ChunkSplitter chunkSplitter() {
        return new ChunkSplitter();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "ingestExecutor")
    public ExecutorService ingestExecutor() {
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "rag-ingest-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(4, tf);
        log.info("ingestExecutor ready — 4 daemon threads");
        return pool;
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public IngestJobRepository ingestJobRepository() {
        // 24h TTL matches the answer cache lifetime (spec §6.3).
        return new IngestJobRepositoryImpl(Duration.ofHours(24));
    }

    @Bean
    @ConditionalOnMissingBean
    public IngestService ingestService(
            ChunkSplitter splitter,
            @Autowired io.github.yysf1949.rag.core.port.EmbeddingGateway embeddingGateway,
            @Autowired io.github.yysf1949.rag.core.port.VectorStore vectorStore,
            IngestJobRepository jobRepository,
            ExecutorService ingestExecutor,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new IngestServiceImpl(splitter, embeddingGateway, vectorStore,
                jobRepository, ingestExecutor, IngestServiceImpl.DEFAULT_EMBED_BATCH, meterRegistry);
    }
}