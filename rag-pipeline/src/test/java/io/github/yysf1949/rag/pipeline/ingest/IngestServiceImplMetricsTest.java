package io.github.yysf1949.rag.pipeline.ingest;

import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.Document;
import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.model.IngestJobStatus;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.splitter.ChunkSplitter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Micrometer metric injection for {@link IngestServiceImpl} — design spec §9.1.
 *
 * <p>Verifies the 6-arg constructor publishes job counters, chunk counters,
 * and pipeline duration timers to the supplied {@link MeterRegistry}.</p>
 */
@ExtendWith(MockitoExtension.class)
class IngestServiceImplMetricsTest {

    private static final String TENANT = "ingestMetricTenant";
    private static final int DIM = 16;

    @Mock EmbeddingGateway embeddingGateway;
    @Mock VectorStore vectorStore;
    IngestJobRepositoryImpl repository;
    ExecutorService executor;
    MeterRegistry meterRegistry;
    IngestServiceImpl service;
    ChunkSplitter splitter;

    @BeforeEach
    void setup() {
        repository = new IngestJobRepositoryImpl();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-ingest-metrics");
            t.setDaemon(true);
            return t;
        });
        splitter = new ChunkSplitter();
        meterRegistry = new SimpleMeterRegistry();
        service = new IngestServiceImpl(splitter, embeddingGateway, vectorStore,
                repository, executor, IngestServiceImpl.DEFAULT_EMBED_BATCH, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        repository.shutdown();
    }

    @Test
    void happyPathIncrementsReadyJobCounterAndChunksCounter() {
        // Use a real splitter with a multi-chunk body (mirrors the
        // docWithChunkCount helper in IngestServiceImplTest) so we get
        // a deterministic 3-chunk doc, then stub the embed gateway to
        // match.
        Document doc = multiChunkDoc("doc-ok", 3);
        int n = splitter.split(doc).size();
        when(embeddingGateway.dimension()).thenReturn(DIM);
        when(embeddingGateway.embedBatch(any())).thenReturn(zeros(n));
        when(vectorStore.upsert(any())).thenReturn(n);

        IngestJob result = service.ingestSync(doc);
        assertEquals(IngestJobStatus.READY, result.status());
        assertEquals(n, result.upsertedChunks());

        double readyJobs = meterRegistry.counter("rag.ingest.jobs.total",
                "tenant", TENANT,
                "status", "READY").count();
        double chunksTotal = meterRegistry.counter("rag.ingest.chunks.total",
                "tenant", TENANT).count();
        assertEquals(1.0, readyJobs, 0.0001, "READY job counter must be 1");
        assertEquals((double) n, chunksTotal, 0.0001,
                "chunks counter must record " + n + " chunks, got " + chunksTotal);
    }

    @Test
    void failedJobIncrementsFailedCounter() {
        Document doc = smallDoc("doc-fail", 2);
        when(embeddingGateway.embedBatch(any())).thenThrow(
                new io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException("down"));

        IngestJob result = service.ingestSync(doc);
        assertEquals(IngestJobStatus.FAILED, result.status());

        double failedJobs = meterRegistry.counter("rag.ingest.jobs.total",
                "tenant", TENANT,
                "status", "FAILED").count();
        assertEquals(1.0, failedJobs, 0.0001, "FAILED job counter must be 1");
    }

    @Test
    void publishIncrementsPublishedCounter() {
        Document doc = multiChunkDoc("doc-pub", 1);
        int n = splitter.split(doc).size();
        when(embeddingGateway.dimension()).thenReturn(DIM);
        when(embeddingGateway.embedBatch(any())).thenReturn(zeros(n));
        when(vectorStore.upsert(any())).thenReturn(n);

        IngestJob ready = service.ingestSync(doc);
        IngestJob published = service.publish(ready.jobId());
        assertEquals(IngestJobStatus.PUBLISHED, published.status(),
                "publish on READY job should reach PUBLISHED, got " + published.status());

        double publishedJobs = meterRegistry.counter("rag.ingest.jobs.total",
                "tenant", TENANT,
                "status", "PUBLISHED").count();
        assertEquals(1.0, publishedJobs, 0.0001, "PUBLISHED job counter must be 1");
    }

    @Test
    void ingestDurationTimerIsRegistered() {
        Document doc = multiChunkDoc("doc-timer", 1);
        int n = splitter.split(doc).size();
        when(embeddingGateway.dimension()).thenReturn(DIM);
        when(embeddingGateway.embedBatch(any())).thenReturn(zeros(n));
        when(vectorStore.upsert(any())).thenReturn(n);

        service.ingestSync(doc);

        var timer = meterRegistry.find("rag.ingest.duration.ms")
                .tag("tenant", TENANT)
                .timer();
        assertNotNull(timer, "rag.ingest.duration.ms{tenant} must be registered");
        assertEquals(1L, timer.count(),
                "duration timer must record 1 sample after one sync ingest");
    }

    @Test
    void noOpConstructorStillWorks() {
        // 5-arg constructor (no MeterRegistry) must keep working.
        IngestServiceImpl legacy = new IngestServiceImpl(
                splitter, embeddingGateway, vectorStore, repository, executor);
        Document doc = multiChunkDoc("doc-legacy", 1);
        int n = splitter.split(doc).size();
        when(embeddingGateway.dimension()).thenReturn(DIM);
        when(embeddingGateway.embedBatch(any())).thenReturn(zeros(n));
        when(vectorStore.upsert(any())).thenReturn(n);
        IngestJob result = legacy.ingestSync(doc);
        assertEquals(IngestJobStatus.READY, result.status());
    }

    // ─── helpers ────────────────────────────────────────────────────────

    /**
     * Build a multi-chunk document using the same unit sentence the
     * IngestServiceImplTest uses — guarantees a deterministic
     * chunk count from the splitter's default settings.
     */
    private static Document multiChunkDoc(String docId, int targetChunks) {
        int bodyChars = 1200 * targetChunks + 200;
        StringBuilder body = new StringBuilder();
        String unit = "退款条款：已付款订单在 24 小时内可全额撤销，运费部分按比例退还。请联系客服协助处理。";
        while (body.length() < bodyChars) {
            body.append(unit);
        }
        return new Document(
                TENANT, "kb-metric-1",
                IngestServiceImpl.encodeDocumentId("kb-metric-1", docId),
                "1", "metric test doc", "https://x",
                Set.of(),
                List.of(new Document.Section("body", body.toString())));
    }

    private static Document smallDoc(String docId, int targetChunks) {
        // 1200 chars ≈ 1 chunk under default ChunkSplitter
        int bodyChars = 1200 * targetChunks + 200;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < bodyChars; i++) body.append('a');
        return new Document(
                TENANT, "kb-metric-1", docId, "1",
                "metric test doc", "https://x",
                Set.of(),
                List.of(new Document.Section("body", body.toString())));
    }

    private static List<float[]> zeros(int n) {
        List<float[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new float[DIM]);
        return out;
    }
}
