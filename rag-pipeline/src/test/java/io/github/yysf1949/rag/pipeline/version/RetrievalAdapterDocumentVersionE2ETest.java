package io.github.yysf1949.rag.pipeline.version;

import io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.EmbeddingChannel;
import io.github.yysf1949.rag.core.model.PermissionMode;
import io.github.yysf1949.rag.core.model.RetrievedChunk;
import io.github.yysf1949.rag.core.port.DocumentVersionService;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.port.RetrievalAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 19 — end-to-end glue test for {@link RetrievalAdapter}'s new
 * per-document version override path on the
 * {@code search(..., Map<String,Long>)} overload.
 *
 * <p>Pure-Java test (no real LLM, no Spring context, no H2):</p>
 * <ul>
 *   <li>{@link VectorStore} + {@link EmbeddingGateway} are in-memory fakes
 *       so we can stuff chunks directly with the metadata shape we want
 *       (incl. {@code documentId} + {@code documentVersion}).</li>
 *   <li>{@link DocumentVersionService} is a Mockito mock — we only care
 *       about how the adapter consumes its return value.</li>
 * </ul>
 *
 * <p>The 5 tests exercise the {@code keepByDocVersion} filter surface
 * added in Phase 19:</p>
 * <ol>
 *   <li>{@link #override_map_pins_doc_to_specific_version()} — override
 *       map is highest priority; docA v1 chunk kept, docA v2 chunk dropped.</li>
 *   <li>{@link #override_map_does_not_affect_other_docs()} — overrides
 *       only touch the docIds in the map; docB flows through unchanged.</li>
 *   <li>{@link #documentVersionService_resolves_minus_one_to_active()} —
 *       with no override, the adapter consults the service for
 *       {@code -1 → active}; only the active version's chunks survive.</li>
 *   <li>{@link #no_service_no_override_keeps_all_chunks()} — backward
 *       compat: no override, no service → all chunks flow through
 *       (Phase 17/18 behavior).</li>
 *   <li>{@link #documentVersionService_throws_NotFound_drops_chunk()} —
 *       no active version for a doc → chunks for that doc are dropped
 *       (graceful, no exception to the caller).</li>
 * </ol>
 */
@DisplayName("RetrievalAdapter Per-Doc Version Override E2E")
class RetrievalAdapterDocumentVersionE2ETest {

    private FakeVectorStore vectorStore;
    private FakeEmbeddingGateway embeddingGateway;
    private DocumentVersionService documentVersionService;
    private RetrievalAdapter adapter;

    @BeforeEach
    void setUp() {
        this.vectorStore = new FakeVectorStore();
        this.embeddingGateway = new FakeEmbeddingGateway();
        this.documentVersionService = mock(DocumentVersionService.class);
        this.adapter = new RetrievalAdapter(
                vectorStore, embeddingGateway, null, documentVersionService);
    }

    // ---- Test 1 ------------------------------------------------------------

    @Test
    @DisplayName("override_map_pins_doc_to_specific_version")
    void override_map_pins_doc_to_specific_version() {
        // Two chunks for docA: v1 + v2. Override says "pin docA to v1".
        // Expect: only v1 chunk survives; v2 chunk dropped.
        vectorStore.put(makeChunk("docA-v1", "t1", "kb1", "docA", 1L,
                "退款规则: 7 天无理由"));
        vectorStore.put(makeChunk("docA-v2", "t1", "kb1", "docA", 2L,
                "退款规则: 15 天无理由"));

        Map<String, Long> overrides = Map.of("docA", 1L);

        List<RetrievedChunk> result = adapter.search(
                "t1", "kb1", 1L,
                "退款规则?", 5, List.of(), overrides);

        assertEquals(1, result.size(),
                "only docA v1 chunk should survive override; got " + result.size());
        assertEquals("docA-v1", result.get(0).chunkId());
        assertTrue(result.get(0).text().contains("7 天"));
        // Service must NOT be consulted when override map has the doc.
        verify(documentVersionService, never())
                .resolveVersion(anyString(), anyString(), anyString(), anyLong());
    }

    // ---- Test 2 ------------------------------------------------------------

    @Test
    @DisplayName("override_map_does_not_affect_other_docs")
    void override_map_does_not_affect_other_docs() {
        // docA has v1+v2 (override pins to v1), docB has v1 (no override).
        // Expect: docA v1 chunk kept, docA v2 dropped, docB v1 kept
        // (override only affects docA; docB is unaffected).
        vectorStore.put(makeChunk("docA-v1", "t1", "kb1", "docA", 1L, "docA v1"));
        vectorStore.put(makeChunk("docA-v2", "t1", "kb1", "docA", 2L, "docA v2"));
        vectorStore.put(makeChunk("docB-v1", "t1", "kb1", "docB", 1L, "docB v1"));

        Map<String, Long> overrides = Map.of("docA", 1L);

        // The override map wins for docA; for docB the adapter will still
        // call DocumentVersionService (priority 2) — stub it.
        when(documentVersionService.resolveVersion("t1", "kb1", "docB", -1L))
                .thenReturn(1L);

        List<RetrievedChunk> result = adapter.search(
                "t1", "kb1", -1L,   // -1 lets service resolve (kb-level)
                "q", 10, List.of(), overrides);

        assertEquals(2, result.size(),
                "docA v1 + docB v1 expected, got " + result.size());
        Map<String, String> byDoc = new HashMap<>();
        for (RetrievedChunk rc : result) {
            byDoc.put(rc.metadata().get("documentId") + "-v"
                    + rc.metadata().get("documentVersion"), rc.chunkId());
        }
        assertEquals("docA-v1", byDoc.get("docA-v1"));
        assertEquals("docB-v1", byDoc.get("docB-v1"));
        assertTrue(!byDoc.containsKey("docA-v2"),
                "docA v2 should be dropped by override; got " + byDoc);
    }

    // ---- Test 3 ------------------------------------------------------------

    @Test
    @DisplayName("documentVersionService_resolves_minus_one_to_active")
    void documentVersionService_resolves_minus_one_to_active() {
        // No override map. Service says: docA active = 3.
        // Store has docA v1, v2, v3. Expect only v3 chunk survives.
        vectorStore.put(makeChunk("docA-v1", "t1", "kb1", "docA", 1L, "docA v1"));
        vectorStore.put(makeChunk("docA-v2", "t1", "kb1", "docA", 2L, "docA v2"));
        vectorStore.put(makeChunk("docA-v3", "t1", "kb1", "docA", 3L, "docA v3"));

        when(documentVersionService.resolveVersion("t1", "kb1", "docA", -1L))
                .thenReturn(3L);

        List<RetrievedChunk> result = adapter.search(
                "t1", "kb1", -1L,   // -1 = "active" (service resolves)
                "q", 10, List.of(), null);

        assertEquals(1, result.size(),
                "only docA v3 (active) should survive; got " + result.size());
        assertEquals("docA-v3", result.get(0).chunkId());
        assertTrue(result.get(0).text().contains("v3"));
        // 3 chunks → service called 3 times (once per chunk; the impl
        // doesn't short-circuit on identical docId — that's a deliberate
        // simplicity choice documented in the impl).
        verify(documentVersionService, times(3))
                .resolveVersion(eq("t1"), eq("kb1"), eq("docA"), eq(-1L));
    }

    // ---- Test 4 ------------------------------------------------------------

    @Test
    @DisplayName("no_service_no_override_keeps_all_chunks")
    void no_service_no_override_keeps_all_chunks() {
        // Backward-compat test: no DocumentVersionService, no override
        // map → adapter falls through, keeps everything Phase 17/18-style.
        // We hand-build a separate adapter with null service so the outer
        // mock isn't touched by this test (state isolation across tests).
        RetrievalAdapter legacyAdapter = new RetrievalAdapter(
                vectorStore, embeddingGateway, null, /*documentVersionService*/ null);

        vectorStore.put(makeChunk("docA-v1", "t1", "kb1", "docA", 1L, "docA v1"));
        vectorStore.put(makeChunk("docA-v2", "t1", "kb1", "docA", 2L, "docA v2"));
        vectorStore.put(makeChunk("docB-v1", "t1", "kb1", "docB", 1L, "docB v1"));

        List<RetrievedChunk> result = legacyAdapter.search(
                "t1", "kb1", 1L, "q", 10, List.of(), null);

        // FakeVectorStore matches by kbVersion on Chunk.documentVersion,
        // so kbVersion=1L returns only the v1 chunks (2 total). The point
        // of this test is that the legacy adapter does NOT additionally
        // filter by per-doc version (no DocumentVersionService, no override).
        assertEquals(2, result.size(),
                "kbVersion=1 → 2 v1 chunks survive; got " + result.size());
        // Verify both surviving chunks are v1 and v2 is dropped.
        List<String> survivingIds = new java.util.ArrayList<>();
        for (RetrievedChunk rc : result) survivingIds.add(rc.chunkId());
        assertTrue(survivingIds.contains("docA-v1"));
        assertTrue(survivingIds.contains("docB-v1"));
        assertTrue(!survivingIds.contains("docA-v2"));
        // The outer adapter's service mock was not touched (no chunks went
        // through it — only the legacyAdapter above did).
        verify(documentVersionService, never())
                .resolveVersion(anyString(), anyString(), anyString(), anyLong());
    }

    // ---- Test 5 ------------------------------------------------------------

    @Test
    @DisplayName("documentVersionService_throws_NotFound_drops_chunk")
    void documentVersionService_throws_NotFound_drops_chunk() {
        // Service throws → doc has never been published. Expect:
        // all chunks for that doc are dropped (no exception bubbles).
        vectorStore.put(makeChunk("docA-v1", "t1", "kb1", "docA", 1L, "docA v1"));
        vectorStore.put(makeChunk("docB-v1", "t1", "kb1", "docB", 1L, "docB v1"));

        // docA has no active version → throw.
        when(documentVersionService.resolveVersion("t1", "kb1", "docA", -1L))
                .thenThrow(new DocumentVersionNotFoundException("t1", "kb1", "docA", -1L));
        // docB has an active version (we don't drop it).
        when(documentVersionService.resolveVersion("t1", "kb1", "docB", -1L))
                .thenReturn(1L);

        List<RetrievedChunk> result = adapter.search(
                "t1", "kb1", -1L, "q", 10, List.of(), null);

        assertEquals(1, result.size(),
                "docA chunk should be dropped; docB should survive; got " + result.size());
        assertEquals("docB-v1", result.get(0).chunkId());
        assertEquals("docB", result.get(0).metadata().get("documentId"));
    }

    // ---- Helpers ------------------------------------------------------------

    private static Chunk makeChunk(String chunkId, String tenantId, String kbId,
                                   String docId, long docVersion, String content) {
        // 16-dim deterministic vector so cosine stays stable.
        float[] v = new float[16];
        for (int i = 0; i < v.length; i++) {
            v[i] = (content.charAt(0) + i) / 100f;
        }
        return new Chunk(
                chunkId,
                tenantId,
                kbId,
                docId,
                String.valueOf(docVersion),   // documentVersion (string in Chunk record)
                "Doc " + docId,
                "/section",
                content,
                Set.of(),                      // permissionTags
                ChunkStatus.ACTIVE,
                Instant.EPOCH,
                "src://fake",
                v,
                EmbeddingChannel.STUB_HASH);
    }

    // ---- Fakes --------------------------------------------------------------

    /**
     * In-memory vector store. We hand-pick the chunks so each chunk's
     * metadata carries {@code documentId} + {@code documentVersion}
     * (the Chunk record stores these as fields, not metadata — but
     * {@link RetrievalAdapter#toRetrievedChunk} copies them into the
     * {@code RetrievedChunk} metadata under the same keys).
     */
    static class FakeVectorStore implements VectorStore {
        final List<Chunk> entries = new ArrayList<>();

        void put(Chunk c) {
            entries.add(c);
        }

        @Override
        public int upsert(List<Chunk> chunks) {
            entries.addAll(chunks);
            return chunks.size();
        }

        @Override
        public List<Chunk> search(float[] query, String tenantId, String kbId,
                                  long kbVersion, List<String> userTags,
                                  PermissionMode mode, int topK) {
            List<Chunk> out = new ArrayList<>();
            for (Chunk c : entries) {
                if (!c.tenantId().equals(tenantId)) continue;
                if (!c.kbId().equals(kbId)) continue;
                // Match by kbVersion by treating Chunk.documentVersion (numeric string)
                // as the kbVersion proxy — same trick RetrievalAdapterKbVersionE2ETest
                // uses. The adapter doesn't care which is which for filtering at this
                // stage; it filters by doc version post-search.
                if (kbVersion >= 0) {
                    try {
                        long chunkDv = Long.parseLong(c.documentVersion());
                        if (chunkDv != kbVersion) continue;
                    } catch (NumberFormatException ignore) {
                        // skip if not numeric — shouldn't happen in this test
                    }
                }
                out.add(c);
                if (out.size() >= topK) break;
            }
            return out;
        }

        @Override
        public int deleteByIds(String tenantId, String kbId, long kbVersion,
                               List<String> chunkIds) {
            return 0;
        }

        @Override
        public void publish(String tenantId, String kbId, long kbVersion) {
            // no-op for this test
        }

        @Override
        public int deprecate(String tenantId, String kbId, long oldKbVersion) {
            int before = entries.size();
            entries.removeIf(c -> c.tenantId().equals(tenantId)
                    && c.kbId().equals(kbId)
                    && Long.parseLong(c.documentVersion()) == oldKbVersion);
            return before - entries.size();
        }
    }

    static class FakeEmbeddingGateway implements EmbeddingGateway {
        @Override
        public int dimension() {
            return 16;
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(t -> {
                float[] v = new float[16];
                for (int i = 0; i < v.length; i++) v[i] = (t.charAt(0) + i) / 100f;
                return v;
            }).toList();
        }

        @Override
        public List<float[]> embedWithoutCache(List<String> texts) {
            return embedBatch(texts);
        }

        @Override
        public void warmCache(Map<String, float[]> entries) {
            // no-op
        }
    }
}