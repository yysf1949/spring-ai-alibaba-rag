package io.github.yysf1949.rag.pipeline.ingest;

import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.Document;
import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.model.IngestJobStatus;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.IngestJobRepository;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.splitter.ChunkSplitter;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IngestServiceImpl#reindexDocument(Document)} —
 * Phase 20 partial re-index.
 *
 * <p>Coverage (5 scenarios):</p>
 * <ol>
 *   <li>Happy path — deletes old chunks, re-ingests, auto-publishes</li>
 *   <li>deleteByDocumentId failure — job FAILED, no ingest attempted</li>
 *   <li>Embedding failure after delete — job FAILED, old chunks already gone</li>
 *   <li>Null document — IllegalArgumentException</li>
 *   <li>Blank kbId — IllegalArgumentException</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class IngestServiceImplReindexTest {

    private static final String TENANT = "reindexTenant";
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
            Thread t = new Thread(r, "test-reindex");
            t.setDaemon(true);
            return t;
        });
        splitter = new ChunkSplitter();
        service = new IngestServiceImpl(splitter, embeddingGateway, vectorStore,
                repository, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        repository.shutdown();
    }

    // ─── 1. Happy path ────────────────────────────────────────────────────

    @Test
    void reindexDocument_happyPath_deletesOldAndReingests() {
        Document doc = sampleDoc("doc-reindex-1");
        stubEmbeddingFor(doc);
        when(vectorStore.deleteByDocumentId(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(3); // simulate 3 old chunks deleted
        when(vectorStore.upsert(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        doNothing().when(vectorStore).publish(anyString(), anyString(), anyLong());

        IngestJob result = service.reindexDocument(doc);

        assertEquals(IngestJobStatus.PUBLISHED, result.status());
        // deleteByDocumentId called once with the plain docId
        verify(vectorStore).deleteByDocumentId(TENANT, "kb-1", "doc-reindex-1", 1L);
        // upsert called at least once
        verify(vectorStore, atLeastOnce()).upsert(anyList());
        // publish called (auto-publish after re-index)
        verify(vectorStore).publish(eq(TENANT), eq("kb-1"), anyLong());
    }

    // ─── 2. deleteByDocumentId failure ────────────────────────────────────

    @Test
    void reindexDocument_deleteFails_jobFailed() {
        Document doc = sampleDoc("doc-reindex-fail");
        when(vectorStore.deleteByDocumentId(anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new VectorStoreUnavailableException("redis down", new RuntimeException()));

        IngestJob result = service.reindexDocument(doc);

        assertEquals(IngestJobStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("reindex-delete"));
        // No ingest attempted after delete failure
        verify(vectorStore, never()).upsert(anyList());
        verify(vectorStore, never()).publish(anyString(), anyString(), anyLong());
    }

    // ─── 3. Embedding failure after delete ────────────────────────────────

    @Test
    void reindexDocument_embeddingFailsAfterDelete_jobFailed() {
        Document doc = sampleDoc("doc-reindex-embed-fail");
        when(vectorStore.deleteByDocumentId(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(2);
        when(embeddingGateway.embedBatch(anyList()))
                .thenThrow(new io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException(
                        "SiliconFlow down", new RuntimeException()));

        IngestJob result = service.reindexDocument(doc);

        assertEquals(IngestJobStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("embedding"));
        // Old chunks were deleted but new ingest failed — that's expected behavior
        verify(vectorStore).deleteByDocumentId(TENANT, "kb-1", "doc-reindex-embed-fail", 1L);
    }

    // ─── 4. Null document ─────────────────────────────────────────────────

    @Test
    void reindexDocument_nullDocument_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.reindexDocument(null));
    }

    // ─── 5. upsert failure after delete + re-split ────────────────────────

    @Test
    void reindexDocument_upsertFails_jobFailed() {
        Document doc = sampleDoc("doc-reindex-upsert-fail");
        when(vectorStore.deleteByDocumentId(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(2);
        when(embeddingGateway.dimension()).thenReturn(DIM);
        when(embeddingGateway.embedBatch(anyList())).thenAnswer(inv -> {
            int n = ((List<?>) inv.getArgument(0)).size();
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < n; i++) vectors.add(randomVector(DIM, i + 1));
            return vectors;
        });
        when(vectorStore.upsert(anyList()))
                .thenThrow(new VectorStoreUnavailableException("store dead", new RuntimeException()));

        IngestJob result = service.reindexDocument(doc);

        assertEquals(IngestJobStatus.FAILED, result.status());
        assertTrue(result.errorMessage().contains("vectorStore"));
        verify(vectorStore).deleteByDocumentId(TENANT, "kb-1", "doc-reindex-upsert-fail", 1L);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private Document sampleDoc(String docId) {
        return new Document(
                TENANT, "kb-1", docId, "1", "title-" + docId,
                "https://example.com/" + docId, Set.of("public"),
                List.of(Document.Section.bodyOnly(
                        "退款政策：已付款订单 24 小时内可全额退款。运费另行计算。")));
    }

    private void stubEmbeddingFor(Document doc) {
        when(embeddingGateway.dimension()).thenReturn(DIM);
        when(embeddingGateway.embedBatch(anyList())).thenAnswer(inv -> {
            int n = ((List<?>) inv.getArgument(0)).size();
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < n; i++) vectors.add(randomVector(DIM, i + 1));
            return vectors;
        });
    }

    private static float[] randomVector(int dim, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = rng.nextFloat() * 2 - 1;
        return v;
    }
}
