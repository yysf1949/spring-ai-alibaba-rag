package io.github.yysf1949.rag.pipeline.version;

import io.github.yysf1949.rag.core.exception.KbVersionNotFoundException;
import io.github.yysf1949.rag.core.model.KbVersionMeta;
import io.github.yysf1949.rag.core.model.PermissionMode;
import io.github.yysf1949.rag.core.model.RetrievedChunk;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.RetrievalPort;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.port.RetrievalAdapter;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the Phase 18 P2 glue:
 * {@link RetrievalAdapter} + {@link H2KbVersionService} + a fake
 * {@link VectorStore}. Verifies that the adapter consults the version
 * service when {@code kbVersion} is the sentinel {@code -1} value.
 *
 * <p>Pure-Java test (no real LLM, no Spring context) — we only need to
 * exercise the version-resolution glue, which runs before any embedding.
 * We short-circuit the embedding with a fake gateway that returns a
 * deterministic vector, and short-circuit the vector store with an
 * in-memory map keyed on (tenant, kb, version).</p>
 */
class RetrievalAdapterKbVersionE2ETest {

    private DataSource dataSource;
    private H2KbVersionService versionService;
    private FakeVectorStore vectorStore;
    private FakeEmbeddingGateway embeddingGateway;
    private RetrievalPort adapter;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:kbv-adapter-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.versionService = new H2KbVersionService(dataSource);
        this.versionService.ensureSchema();
        this.vectorStore = new FakeVectorStore();
        this.embeddingGateway = new FakeEmbeddingGateway();
        this.adapter = new RetrievalAdapter(vectorStore, embeddingGateway, versionService);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection c = dataSource.getConnection();
             var s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS DELETE FILES");
        }
    }

    @Test
    void kbVersionNegativeIsResolvedViaServiceToActive() {
        // Register two versions, publish v1 as active.
        versionService.registerVersion("t1", "kb1", 1L,
                KbVersionMeta.Status.STAGING, "v1");
        versionService.registerVersion("t1", "kb1", 2L,
                KbVersionMeta.Status.STAGING, "v2");
        versionService.publish("t1", "kb1", 1L);

        // Stuff both versions into the fake store.
        vectorStore.put("t1", "kb1", 1L, "kb1 has 2 versions. v1 is current.");
        vectorStore.put("t1", "kb1", 2L, "kb1 has 2 versions. v2 is staged.");

        // Request with kbVersion = -1 (sentinel: "use active")
        List<RetrievedChunk> result = adapter.search("t1", "kb1", -1L,
                "what does kb1 say?", 5, List.of());

        // Adapter resolved -1 → 1 (active), so we get v1's content.
        assertEquals(1, result.size());
        assertTrue(result.get(0).text().contains("v1 is current"),
                "expected v1 active content, got: " + result.get(0).text());
        assertEquals(1L, result.get(0).kbVersion(),
                "expected effective kbVersion=1 in result metadata");
    }

    @Test
    void kbVersionSpecificIsRespectedWhenSet() {
        versionService.registerVersion("t1", "kb1", 1L,
                KbVersionMeta.Status.STAGING, "v1");
        versionService.registerVersion("t1", "kb1", 2L,
                KbVersionMeta.Status.STAGING, "v2");
        versionService.publish("t1", "kb1", 1L);

        vectorStore.put("t1", "kb1", 1L, "v1 content (deprecated but still queryable)");
        vectorStore.put("t1", "kb1", 2L, "v2 content (staged but queryable)");

        // Request with kbVersion = 2L (specific, not active).
        List<RetrievedChunk> result = adapter.search("t1", "kb1", 2L,
                "what does kb1 say?", 5, List.of());

        assertEquals(1, result.size());
        assertTrue(result.get(0).text().contains("v2 content"),
                "expected v2 content, got: " + result.get(0).text());
        assertEquals(2L, result.get(0).kbVersion());
    }

    @Test
    void kbVersionNoActiveAndNegativeReturnsEmpty() {
        // Register but never publish
        versionService.registerVersion("t1", "kb1", 1L,
                KbVersionMeta.Status.STAGING, "v1");
        vectorStore.put("t1", "kb1", 1L, "v1 content (but no active pointer)");

        // No active version → adapter returns empty (graceful 200 + empty)
        List<RetrievedChunk> result = adapter.search("t1", "kb1", -1L,
                "what does kb1 say?", 5, List.of());

        assertTrue(result.isEmpty(),
                "expected empty list when no active version, got " + result.size());
    }

    @Test
    void kbVersionUnknownReturnsEmptyList() {
        // No chunks for version 99 → adapter returns empty list (no exception).
        // (KbVersionNotFoundException is only thrown for negative kbVersion that
        // has no active pointer — for specific versions that exist as metadata
        // but have no chunks, the right behavior is empty result, not 404.)
        List<RetrievedChunk> result = adapter.search("t1", "kb1", 99L,
                "x", 5, List.of());
        assertTrue(result.isEmpty(),
                "expected empty list for nonexistent kbVersion, got " + result.size());
    }

    // ---- Fakes --------------------------------------------------------------

    /** Stores (tenant, kb, version) → content as a flat map for easy filtering. */
    static class FakeVectorStore implements VectorStore {
        record Key(String tenant, String kb, long version) {}
        final Map<Key, String> entries = new HashMap<>();

        void put(String tenant, String kb, long version, String content) {
            entries.put(new Key(tenant, kb, version), content);
        }

        @Override
        public int upsert(List<io.github.yysf1949.rag.core.model.Chunk> chunks) {
            // Adapter doesn't use upsert in this test.
            return 0;
        }

        @Override
        public List<io.github.yysf1949.rag.core.model.Chunk> search(
                float[] query, String tenantId, String kbId, long kbVersion,
                List<String> userTags, PermissionMode mode, int topK) {
            // Map entries → Chunk records so the adapter's score-recompute
            // and Chunk→RetrievedChunk mapping can exercise real code.
            List<io.github.yysf1949.rag.core.model.Chunk> out = new ArrayList<>();
            int idx = 0;
            for (var entry : entries.entrySet()) {
                Key k = entry.getKey();
                if (!k.tenant().equals(tenantId) || !k.kb().equals(kbId) || k.version() != kbVersion) {
                    continue;
                }
                float[] v = new float[16];
                for (int i = 0; i < v.length; i++) v[i] = (entry.getValue().charAt(0) + i) / 100f;
                out.add(new io.github.yysf1949.rag.core.model.Chunk(
                        "chunk-" + idx++,
                        k.tenant(),
                        k.kb(),
                        "doc-" + k.version(),
                        String.valueOf(k.version()),  // documentVersion is the numeric KB version
                        "Doc v" + k.version(),
                        "/section",
                        entry.getValue(),
                        java.util.Set.of(),                       // permissionTags
                        io.github.yysf1949.rag.core.model.ChunkStatus.ACTIVE,
                        java.time.Instant.EPOCH,                  // publishedAt
                        "src://fake",
                        v,
                        io.github.yysf1949.rag.core.model.EmbeddingChannel.STUB_HASH));
                if (out.size() >= topK) break;
            }
            return out;
        }

        @Override
        public int deleteByIds(String tenantId, String kbId, long kbVersion, List<String> chunkIds) {
            return 0;
        }

        @Override
        public void publish(String tenantId, String kbId, long kbVersion) {
            // not exercised
        }

        @Override
        public int deprecate(String tenantId, String kbId, long oldKbVersion) {
            int before = entries.size();
            entries.entrySet().removeIf(e ->
                    e.getKey().tenant().equals(tenantId)
                            && e.getKey().kb().equals(kbId)
                            && e.getKey().version() == oldKbVersion);
            return before - entries.size();
        }

        @Override
        public int deleteByDocumentId(String tenantId, String kbId, String documentId, long kbVersion) {
            int before = entries.size();
            entries.entrySet().removeIf(e ->
                    e.getKey().tenant().equals(tenantId)
                            && e.getKey().kb().equals(kbId));
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
        public void warmCache(Map<String, float[]> entries) { /* no-op */ }
    }
}