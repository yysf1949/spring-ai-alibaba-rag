package io.github.yysf1949.rag.pipeline.ingest;

import io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException;
import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import io.github.yysf1949.rag.core.model.Document;
import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.model.IngestJobStatus;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.logging.PipelineMdc;
import io.github.yysf1949.rag.pipeline.splitter.ChunkSplitter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * MDC instrumentation for {@link IngestServiceImpl} — design spec §9.2.
 *
 * <p>Verifies:
 * <ul>
 *   <li>{@code jobId} is pinned on the MDC for the whole ingest pipeline
 *       (sync + async + publish), and cleared after.</li>
 *   <li>{@code stage} flips split → embed → upsert → publish in order.</li>
 *   <li>The async executor restores the HTTP-thread MDC (tenant + requestId)
 *       on the worker thread — otherwise async log lines lose correlation.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IngestServiceImplMdcTest {

    private static final String TENANT = "ingestMdcTenant";
    private static final String REQ_ID = "req-mdc-789";
    private static final int DIM = 16;

    @Mock EmbeddingGateway embeddingGateway;
    @Mock VectorStore vectorStore;
    IngestJobRepositoryImpl repository;
    ExecutorService executor;
    IngestServiceImpl service;
    ChunkSplitter splitter;

    @BeforeEach
    void setUp() {
        repository = new IngestJobRepositoryImpl();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-ingest-mdc");
            t.setDaemon(true);
            return t;
        });
        splitter = new ChunkSplitter();
        service = new IngestServiceImpl(splitter, embeddingGateway, vectorStore,
                repository, executor, IngestServiceImpl.DEFAULT_EMBED_BATCH,
                new SimpleMeterRegistry());
        // Simulate MdcTenantFilter at the HTTP boundary.
        MDC.put(PipelineMdc.KEY_TENANT, TENANT);
        MDC.put(PipelineMdc.KEY_REQUEST_ID, REQ_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        executor.shutdownNow();
        repository.shutdown();
    }

    @Test
    void jobIdPinnedDuringSync_andClearedAfter() {
        AtomicReference<String> seenJobId = new AtomicReference<>();
        // Capture MDC inside embeddingGateway.embedBatch — that runs inside
        // the "embed" stage wrapper on the calling (sync) thread.
        Document doc = smallDoc("doc-sync", 1);
        int n = splitter.split(doc).size();
        when(embeddingGateway.dimension()).thenReturn(DIM);
        doAnswer(inv -> {
            seenJobId.set(MDC.get(PipelineMdc.KEY_JOB_ID));
            return zeros(((List<?>) inv.getArgument(0)).size());
        }).when(embeddingGateway).embedBatch(any());
        when(vectorStore.upsert(any())).thenReturn(n);

        IngestJob result = service.ingestSync(doc);
        assertEquals(IngestJobStatus.READY, result.status());
        assertNotNull(seenJobId.get(), "jobId must be visible during embed stage");
        assertEquals(result.jobId(), seenJobId.get(),
                "the jobId seen by embed must match the one returned to caller");
        assertNull(MDC.get(PipelineMdc.KEY_JOB_ID),
                "jobId must be cleared after ingestSync returns, got " + MDC.get(PipelineMdc.KEY_JOB_ID));
    }

    @Test
    void stageFlipsSplitEmbedUpsert() {
        List<String> stages = new ArrayList<>();
        Document doc = smallDoc("doc-stages", 1);
        int n = splitter.split(doc).size();
        when(embeddingGateway.dimension()).thenReturn(DIM);
        doAnswer(inv -> {
            stages.add(MDC.get(PipelineMdc.KEY_STAGE));
            return zeros(((List<?>) inv.getArgument(0)).size());
        }).when(embeddingGateway).embedBatch(any());
        when(vectorStore.upsert(any())).thenAnswer(inv -> {
            stages.add(MDC.get(PipelineMdc.KEY_STAGE));
            return ((List<?>) inv.getArgument(0)).size();
        });

        service.ingestSync(doc);
        assertEquals(List.of("embed", "upsert"), stages,
                "stage must flip split → embed → upsert; embed + upsert captured, got " + stages);
    }

    @Test
    void stageClearedAfterEmbeddingFailure() {
        when(embeddingGateway.embedBatch(any())).thenThrow(
                new EmbeddingUnavailableException("down"));
        IngestJob result = service.ingestSync(smallDoc("doc-fail", 1));
        assertEquals(IngestJobStatus.FAILED, result.status());
        assertNull(MDC.get(PipelineMdc.KEY_STAGE),
                "stage must be cleared even on FAILED path, got " + MDC.get(PipelineMdc.KEY_STAGE));
        assertNull(MDC.get(PipelineMdc.KEY_JOB_ID),
                "jobId must be cleared even on FAILED path, got " + MDC.get(PipelineMdc.KEY_JOB_ID));
    }

    @Test
    void stageClearedAfterUpsertFailure() {
        Document doc = smallDoc("doc-vs-fail", 1);
        int n = splitter.split(doc).size();
        when(embeddingGateway.dimension()).thenReturn(DIM);
        when(embeddingGateway.embedBatch(any())).thenReturn(zeros(n));
        when(vectorStore.upsert(any())).thenThrow(new VectorStoreUnavailableException("store dead"));
        IngestJob result = service.ingestSync(doc);
        assertEquals(IngestJobStatus.FAILED, result.status());
        assertNull(MDC.get(PipelineMdc.KEY_STAGE),
                "upsert failure path must clear stage MDC");
        assertNull(MDC.get(PipelineMdc.KEY_JOB_ID),
                "upsert failure path must clear jobId MDC");
    }

    @Test
    void asyncExecutorRestoresHttpThreadMdc() throws Exception {
        // Async runs on the executor's worker thread, NOT the calling
        // thread. The worker must restore the HTTP-thread MDC (tenant +
        // requestId) so log lines emitted inside runPipeline stay
        // correlated with the originating HTTP request.
        CountDownLatch embedRan = new CountDownLatch(1);
        AtomicReference<Map<String, String>> asyncMdcAtEmbed = new AtomicReference<>();
        Document doc = smallDoc("doc-async", 1);
        int n = splitter.split(doc).size();
        when(embeddingGateway.dimension()).thenReturn(DIM);
        doAnswer(inv -> {
            asyncMdcAtEmbed.set(MDC.getCopyOfContextMap());
            embedRan.countDown();
            return zeros(((List<?>) inv.getArgument(0)).size());
        }).when(embeddingGateway).embedBatch(any());
        when(vectorStore.upsert(any())).thenReturn(n);

        IngestJob submitted = service.ingestAsync(doc);
        assertNotNull(submitted.jobId());
        assertTrue(embedRan.await(5, TimeUnit.SECONDS),
                "executor must run embedBatch within 5s");
        Map<String, String> seen = asyncMdcAtEmbed.get();
        assertNotNull(seen, "MDC inside executor must be non-null");
        assertEquals(TENANT, seen.get(PipelineMdc.KEY_TENANT),
                "executor thread must see tenant from HTTP boundary, got MDC=" + seen);
        assertEquals(REQ_ID, seen.get(PipelineMdc.KEY_REQUEST_ID),
                "executor thread must see requestId from HTTP boundary, got MDC=" + seen);
        assertEquals(submitted.jobId(), seen.get(PipelineMdc.KEY_JOB_ID),
                "executor thread must see jobId from the ingest call");
        // Wait for async to drain so we don't leak the executor's MDC.
        Thread.sleep(200);
        assertNull(MDC.get(PipelineMdc.KEY_JOB_ID),
                "jobId must NOT leak onto the HTTP thread after ingestAsync returns");
    }

    @Test
    void publishStageSetsJobIdAndStage() {
        // Stage a doc to READY first (sync).
        Document doc = smallDoc("doc-pub", 1);
        int n = splitter.split(doc).size();
        lenient().when(embeddingGateway.dimension()).thenReturn(DIM);
        lenient().when(embeddingGateway.embedBatch(any())).thenReturn(zeros(n));
        lenient().when(vectorStore.upsert(any())).thenReturn(n);
        IngestJob ready = service.ingestSync(doc);

        // Now publish — capture MDC inside vectorStore.publish.
        AtomicReference<Map<String, String>> publishMdc = new AtomicReference<>();
        doAnswer(inv -> {
            publishMdc.set(MDC.getCopyOfContextMap());
            return null;
        }).when(vectorStore).publish(any(), any(), anyLong());

        service.publish(ready.jobId());

        Map<String, String> seen = publishMdc.get();
        assertNotNull(seen, "publish must run inside MDC context");
        assertEquals(ready.jobId(), seen.get(PipelineMdc.KEY_JOB_ID),
                "publish-stage MDC must carry the jobId");
        assertEquals("publish", seen.get(PipelineMdc.KEY_STAGE),
                "publish-stage MDC must carry stage=publish");
        assertNull(MDC.get(PipelineMdc.KEY_JOB_ID),
                "publish must clear jobId after returning");
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static Document smallDoc(String docId, int targetChunks) {
        int bodyChars = 1200 * targetChunks + 200;
        StringBuilder body = new StringBuilder();
        String unit = "退款条款：已付款订单在 24 小时内可全额撤销，运费部分按比例退还。请联系客服协助处理。";
        while (body.length() < bodyChars) body.append(unit);
        return new Document(
                TENANT, "kb-mdc-1",
                IngestServiceImpl.encodeDocumentId("kb-mdc-1", docId),
                "1", "mdc test doc", "https://x",
                Set.of(),
                List.of(new Document.Section("body", body.toString())));
    }

    private static List<float[]> zeros(int n) {
        List<float[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new float[DIM]);
        return out;
    }
}