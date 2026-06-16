package io.github.yysf1949.rag.pipeline.ingest;

import io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.Document;
import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.model.IngestJobStatus;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.IngestJobRepository;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.splitter.ChunkSplitter;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IngestServiceImpl} — drives the full pipeline
 * with mocked {@link EmbeddingGateway} and {@link VectorStore}.
 *
 * <p>Coverage (10 scenarios):</p>
 * <ol>
 *   <li>Happy path — sync ingest produces READY with all chunks upserted</li>
 *   <li>Embedding failure — entire job is FAILED, no upsert is attempted</li>
 *   <li>VectorStore partial failure — failedChunks counted, job still READY</li>
 *   <li>Chunk cap exceeded — 10_001 chunks → FAILED with spec §10 message</li>
 *   <li>Empty document — 0 chunks → READY, no embedding or upsert calls</li>
 *   <li>Dimension-mismatch on one vector — chunk dropped + counted</li>
 *   <li>Async path — submit returns PENDING immediately, status reaches READY</li>
 *   <li>publish() — READY → PUBLISHED, calls VectorStore.publish exactly once</li>
 *   <li>publish() non-READY — IllegalStateException</li>
 *   <li>Unknown jobId — publish throws IllegalArgumentException</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class IngestServiceImplTest {

    private static final String TENANT = "ingestTenant";
    private static final int DIM = 1536;

    @Mock EmbeddingGateway embeddingGateway;
    @Mock VectorStore vectorStore;
    IngestJobRepositoryImpl repository;
    ExecutorService executor;
    IngestServiceImpl service;
    ChunkSplitter splitter;

    @BeforeEach
    void setup() {
        repository = new IngestJobRepositoryImpl();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-ingest");
            t.setDaemon(true);
            return t;
        });
        splitter = new ChunkSplitter();
        service = new IngestServiceImpl(splitter, embeddingGateway, vectorStore,
                repository, executor);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        executor.shutdownNow();
        repository.shutdown();
    }

    /**
     * Build a document with body big enough to produce {@code targetChunks}
     * chunks under the default splitter, then stub the embedding gateway
     * with exactly that many vectors. This keeps the pipeline happy
     * regardless of the splitter's exact size thresholds.
     */
    private Document docWithChunkCount(String docId, int targetChunks) {
        // 1200 chars per chunk under defaults; oversize slightly so the
        // splitter produces at least targetChunks chunks.
        int bodyChars = 1200 * targetChunks + 200;
        StringBuilder body = new StringBuilder();
        String unit = "退款条款：已付款订单在 24 小时内可全额撤销，运费部分按比例退还。请联系客服协助处理。";
        while (body.length() < bodyChars) {
            body.append(unit);
        }
        return new Document(
                TENANT, "kb-1",
                IngestServiceImpl.encodeDocumentId("kb-1", docId),
                "1", "title-" + docId, "https://example.com/" + docId,
                Set.of("public"),
                List.of(Document.Section.bodyOnly(body.toString())));
    }

    /** Stub the embedding gateway with one valid vector per chunk in {@code doc}. */
    private void stubEmbeddingFor(Document doc) {
        int n = splitter.split(doc).size();
        stubEmbeddingWithValidVectors(n);
    }

    // ─── 1. happy path ────────────────────────────────────────────────────

    @Test
    void ingestSync_happyPath() {
        Document doc = sampleDoc("doc-1");
        stubEmbeddingFor(doc);
        when(vectorStore.upsert(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        IngestJob result = service.ingestSync(doc);

        assertEquals(IngestJobStatus.READY, result.status());
        assertEquals(1, result.totalChunks());
        assertEquals(1, result.embeddedChunks());
        assertEquals(1, result.upsertedChunks());
        assertEquals(0, result.failedChunks());
        assertNull(result.errorMessage());
        verify(vectorStore, times(1)).upsert(anyList());
    }

    // ─── 2. embedding failure ──────────────────────────────────────────────

    @Test
    void ingestSync_embeddingFailure_marksJobFailed() {
        Document doc = sampleDoc("doc-2");
        when(embeddingGateway.embedBatch(anyList()))
                .thenThrow(new EmbeddingUnavailableException("dashscope down"));

        IngestJob result = service.ingestSync(doc);

        assertEquals(IngestJobStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("embedding"),
                "error message should mention embedding subsystem: " + result.errorMessage());
        verify(vectorStore, never()).upsert(anyList());
    }

    // ─── 3. vector store partial failure ───────────────────────────────────

    @Test
    void ingestSync_vectorStorePartialUpsert_failedChunksCounted() {
        Document doc = docWithChunkCount("doc-3", 3);
        int n = splitter.split(doc).size();
        stubEmbeddingWithValidVectors(n);
        // Store only writes 2 of n.
        when(vectorStore.upsert(anyList())).thenReturn(2);

        IngestJob result = service.ingestSync(doc);

        assertEquals(IngestJobStatus.READY, result.status());
        assertEquals(2, result.upsertedChunks());
        assertEquals(n - 2, result.failedChunks());
    }

    // ─── 4. chunk cap exceeded ─────────────────────────────────────────────

    @Test
    void ingestSync_chunkCapExceeded_marksFailed() {
        // Stub a splitter that always returns MAX+1 chunks.
        ChunkSplitter huge = mock(ChunkSplitter.class);
        List<Chunk> oversized = new ArrayList<>();
        for (int i = 0; i < IngestServiceImpl.MAX_CHUNKS_PER_DOCUMENT + 1; i++) {
            oversized.add(stubChunk("c" + i, "body " + i));
        }
        when(huge.split(any(Document.class))).thenReturn(oversized);
        IngestServiceImpl capped = new IngestServiceImpl(huge, embeddingGateway, vectorStore,
                repository, executor);

        IngestJob result = capped.ingestSync(sampleDoc("doc-cap"));

        assertEquals(IngestJobStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("cap"),
                "should reference the cap in the error: " + result.errorMessage());
        verify(embeddingGateway, never()).embedBatch(anyList());
        verify(vectorStore, never()).upsert(anyList());
    }

    // ─── 5. empty document ─────────────────────────────────────────────────

    @Test
    void ingestSync_emptyDocument_readyWithoutEmbedding() {
        Document empty = new Document(TENANT, "kb", "doc-empty", "1", "t", "u", Set.of(), List.of());
        IngestJob result = service.ingestSync(empty);

        assertEquals(IngestJobStatus.READY, result.status());
        assertEquals(0, result.totalChunks());
        assertEquals(0, result.embeddedChunks());
        assertEquals(0, result.upsertedChunks());
        verifyNoInteractions(embeddingGateway, vectorStore);
    }

    // ─── 6. dimension-mismatch on one vector ───────────────────────────────

    @Test
    void ingestSync_dimensionMismatchOnOneVector_dropped() {
        Document doc = docWithChunkCount("doc-dim", 3);
        int n = splitter.split(doc).size();
        when(embeddingGateway.dimension()).thenReturn(DIM);
        // Build a list of n vectors; the second is the wrong dim.
        List<float[]> vectors = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            vectors.add(i == 1 ? randomVector(512, i + 1) : randomVector(DIM, i + 1));
        }
        when(embeddingGateway.embedBatch(anyList())).thenReturn(vectors);
        when(vectorStore.upsert(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        IngestJob result = service.ingestSync(doc);

        assertEquals(IngestJobStatus.READY, result.status());
        assertEquals(n, result.totalChunks());
        assertEquals(n - 1, result.upsertedChunks(), "1 chunk should have been dropped");
        assertTrue(result.failedChunks() >= 1, "failedChunks should account for the dropped chunk");
    }

    // ─── 7. async path ─────────────────────────────────────────────────────

    @Test
    void ingestAsync_submitReturnsPending_thenReachesReady() throws Exception {
        Document doc = docWithChunkCount("doc-async", 2);
        stubEmbeddingFor(doc);
        when(vectorStore.upsert(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        IngestJob submitted = service.ingestAsync(doc);
        assertEquals(IngestJobStatus.PENDING, submitted.status(),
                "async submit must return PENDING, got " + submitted.status());

        // Wait for the executor to drain.
        executor.shutdown();
        boolean done = executor.awaitTermination(5, TimeUnit.SECONDS);
        assertTrue(done, "executor should have finished within 5s");

        IngestJob finalJob = service.getJob(submitted.jobId()).orElseThrow();
        assertEquals(IngestJobStatus.READY, finalJob.status());
    }

    // ─── 8. publish happy path ─────────────────────────────────────────────

    @Test
    void publish_readyToPublished_callsVectorStorePublish() {
        Document doc = sampleDoc("doc-publish");
        stubEmbeddingFor(doc);
        when(vectorStore.upsert(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        IngestJob ready = service.ingestSync(doc);
        assertEquals(IngestJobStatus.READY, ready.status());

        IngestJob published = service.publish(ready.jobId());

        assertEquals(IngestJobStatus.PUBLISHED, published.status());
        verify(vectorStore, times(1)).publish(eq(TENANT), anyString(), anyLong());
    }

    // ─── 9. publish non-READY ──────────────────────────────────────────────

    @Test
    void publish_nonReadyStatus_throws() {
        Document doc = sampleDoc("doc-bad-publish");
        stubEmbeddingFor(doc);
        when(vectorStore.upsert(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        IngestJob ready = service.ingestSync(doc);
        // Simulate the operator resetting state to PENDING (e.g. rollback
        // script in production).
        repository.save(ready.withStatus(IngestJobStatus.PENDING));

        assertThrows(IllegalStateException.class, () -> service.publish(ready.jobId()));
    }

    // ─── 10. unknown jobId ─────────────────────────────────────────────────

    @Test
    void publish_unknownJobId_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.publish("no-such-job"));
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private Document sampleDoc(String docId) {
        return new Document(
                TENANT, "kb-1",
                IngestServiceImpl.encodeDocumentId("kb-1", docId),
                "1", "title-" + docId, "https://example.com/" + docId,
                Set.of("public"),
                List.of(Document.Section.bodyOnly(
                        "退款政策：已付款订单 24 小时内可全额退款。运费另行计算。")));
    }

    private void stubEmbeddingWithValidVectors(int n) {
        when(embeddingGateway.dimension()).thenReturn(DIM);
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < n; i++) vectors.add(randomVector(DIM, i + 1));
        when(embeddingGateway.embedBatch(anyList())).thenReturn(vectors);
    }

    private static float[] randomVector(int dim, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = rng.nextFloat() * 2 - 1;
        return v;
    }

    private static Chunk stubChunk(String id, String body) {
        return new Chunk(
                id, TENANT, "kb-1", "doc-stub", "1", "t", "/path",
                body, Set.of(), ChunkStatus.STAGING, null, "uri", new float[0]);
    }
}
