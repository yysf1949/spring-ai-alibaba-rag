package io.github.yysf1949.rag.embedding.stub;

import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.LlmService;
import io.github.yysf1949.rag.core.port.RerankService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the three stub adapters. Confirms the contract
 * guarantees they make to {@code QAServiceImpl}'s degradation ladder:
 * deterministic, never-null, never-throw on well-formed inputs.
 */
class StubAdaptersTest {

    @Test
    void embeddingGateway_returnsCorrectDimension() {
        EmbeddingGateway gw = new StubEmbeddingGateway();
        assertEquals(StubEmbeddingGateway.DIM, gw.dimension());
    }

    @Test
    void embeddingGateway_batchCachesResults() {
        EmbeddingGateway gw = new StubEmbeddingGateway();
        List<float[]> first = gw.embedBatch(List.of("hello", "world"));
        List<float[]> second = gw.embedBatch(List.of("hello", "world"));

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, first.size());
        assertEquals(2, second.size());
        // same text → same vector reference (cache hit)
        assertSame(first.get(0), second.get(0));
        assertSame(first.get(1), second.get(1));
        // different texts → different vectors
        assertFalse(java.util.Arrays.equals(first.get(0), first.get(1)));
    }

    @Test
    void embeddingGateway_withoutCacheAlwaysAllocates() {
        EmbeddingGateway gw = new StubEmbeddingGateway();
        List<float[]> a = gw.embedWithoutCache(List.of("hello"));
        List<float[]> b = gw.embedWithoutCache(List.of("hello"));

        assertEquals(1, a.size());
        assertEquals(1, b.size());
        // same content, different allocations
        assertNotNull(a.get(0));
        assertNotNull(b.get(0));
        assertFalse(a.get(0) == b.get(0));
    }

    @Test
    void embeddingGateway_warmCache_seedsEntries() {
        EmbeddingGateway gw = new StubEmbeddingGateway();
        float[] v = new float[]{1.0f, 2.0f};
        gw.warmCache(Map.of("k", v));

        List<float[]> r = gw.embedBatch(List.of("k"));
        assertSame(v, r.get(0));
    }

    @Test
    void rerankService_returnsFirstTopN() {
        RerankService rr = new StubRerankService();
        var c1 = chunk("c1");
        var c2 = chunk("c2");
        var c3 = chunk("c3");
        List<io.github.yysf1949.rag.core.model.Chunk> out = rr.rerank("q", List.of(c1, c2, c3), 2);
        assertEquals(2, out.size());
        assertSame(c1, out.get(0));
        assertSame(c2, out.get(1));
    }

    @Test
    void rerankService_emptyInput_returnsEmpty() {
        RerankService rr = new StubRerankService();
        assertEquals(List.of(), rr.rerank("q", List.of(), 5));
    }

    @Test
    void rerankService_topNLargerThanCandidates_returnsAll() {
        RerankService rr = new StubRerankService();
        var c1 = chunk("c1");
        assertEquals(List.of(c1), rr.rerank("q", List.of(c1), 100));
    }

    @Test
    void llmService_neverReturnsNull() {
        LlmService ll = new StubLlmService();
        // non-null prompts → non-null response
        assertNotNull(ll.generateAnswer("t1", ""));
        assertNotNull(ll.generateAnswer("t1", "abc"));
        // (null prompt is caller-bug; the LlmService contract says "never
        // return null" — it does not promise null-prompt tolerance.)
        assertTrue(ll.generateAnswer("t1", "abc").contains("3"));
    }

    @Test
    void llmService_modelIdIsStable() {
        LlmService ll = new StubLlmService();
        assertEquals("stub-llm", ll.modelId());
    }

    private static io.github.yysf1949.rag.core.model.Chunk chunk(String id) {
        return new io.github.yysf1949.rag.core.model.Chunk(
                id, "t1", "kb1", "doc1", "1", "title", "anchor",
                "body", java.util.Set.of(), io.github.yysf1949.rag.core.model.ChunkStatus.ACTIVE,
                java.time.Instant.now(), "src", new float[16], null);
    }
}