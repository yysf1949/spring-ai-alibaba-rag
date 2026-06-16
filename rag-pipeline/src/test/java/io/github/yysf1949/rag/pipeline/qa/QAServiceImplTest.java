package io.github.yysf1949.rag.pipeline.qa;

import io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException;
import io.github.yysf1949.rag.core.exception.LlmUnavailableException;
import io.github.yysf1949.rag.core.exception.RerankUnavailableException;
import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.KbVersion;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.AnswerCache;
import io.github.yysf1949.rag.core.port.EmbeddingCache;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.HotQuestionProvider;
import io.github.yysf1949.rag.core.port.LlmService;
import io.github.yysf1949.rag.core.port.RerankService;
import io.github.yysf1949.rag.core.port.RewriteService;
import io.github.yysf1949.rag.core.port.RewriteService.RewriteResult;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.context.ContextAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests for {@link QAServiceImpl} — design spec §7.1 + §7.5.
 * Each test mocks the full port surface; the assertions focus on the
 * <b>degradation ladder</b> (spec §7.5), which is the real engineering value.
 */
class QAServiceImplTest {

    private static final float[] ZERO_VEC = new float[16];

    // ─── test doubles ─────────────────────────────────────────────────────

    static class StubRewrite implements RewriteService {
        String lastTenant;
        String lastText;
        RewriteResult toReturn = new RewriteResult("rewritten-text", 0.9, false);

        @Override
        public RewriteResult rewrite(String tenantId, String rawText) {
            this.lastTenant = tenantId;
            this.lastText = rawText;
            return toReturn;
        }
    }

    static class StubAnswerCache implements AnswerCache {
        final ConcurrentHashMap<String, Answer> store = new ConcurrentHashMap<>();
        AtomicInteger getCalls = new AtomicInteger();
        AtomicInteger putCalls = new AtomicInteger();
        RuntimeException getToThrow = null;
        RuntimeException putToThrow = null;

        @Override
        public Optional<Answer> get(String tenantId, String queryHash) {
            getCalls.incrementAndGet();
            if (getToThrow != null) throw getToThrow;
            return Optional.ofNullable(store.get(tenantId + "::" + queryHash));
        }

        @Override
        public boolean put(String tenantId, String queryHash, Answer answer) {
            putCalls.incrementAndGet();
            if (putToThrow != null) throw putToThrow;
            store.put(tenantId + "::" + queryHash, answer);
            return true;
        }

        @Override
        public long invalidateTenant(String tenantId) {
            int before = store.size();
            store.keySet().removeIf(k -> k.startsWith(tenantId + "::"));
            return before - store.size();
        }

        @Override
        public double hitRatio(String tenantId) {
            return 0.0;
        }
    }

    static class StubEmbedCache implements EmbeddingCache {
        final ConcurrentHashMap<String, float[]> store = new ConcurrentHashMap<>();
        RuntimeException toThrowOnGet = null;

        @Override
        public float[] get(String textHash) {
            if (toThrowOnGet != null) throw toThrowOnGet;
            return store.get(textHash);
        }

        @Override
        public List<float[]> getMany(List<String> textHashes) {
            List<float[]> out = new ArrayList<>();
            for (String h : textHashes) out.add(store.get(h));
            return out;
        }

        @Override
        public void put(String textHash, float[] vector) {
            store.put(textHash, vector);
        }

        @Override
        public void putMany(Map<String, float[]> entries) {
            store.putAll(entries);
        }
    }

    static class StubEmbedGateway implements EmbeddingGateway {
        AtomicInteger batchCalls = new AtomicInteger();
        RuntimeException toThrow = null;
        List<float[]> toReturn = null;
        int dimension = 16;

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            batchCalls.incrementAndGet();
            if (toThrow != null) throw toThrow;
            if (toReturn != null) return toReturn;
            // Default: one zero-vector per text.
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) out.add(new float[dimension]);
            return out;
        }

        @Override
        public List<float[]> embedWithoutCache(List<String> texts) {
            return embedBatch(texts);
        }

        @Override
        public int dimension() {
            return dimension;
        }

        @Override
        public void warmCache(Map<String, float[]> entries) {
            // no-op
        }
    }

    static class StubVectorStore implements VectorStore {
        AtomicInteger searchCalls = new AtomicInteger();
        List<Chunk> toReturn = new ArrayList<>();
        RuntimeException toThrow = null;

        @Override
        public int upsert(List<Chunk> chunks) { return chunks.size(); }

        @Override
        public int deleteByIds(String tenantId, String kbId, long kbVersion, List<String> chunkIds) {
            return chunkIds.size();
        }

        @Override
        public List<Chunk> search(
                float[] queryVector,
                String tenantId,
                String kbId,
                long kbVersion,
                List<String> userPermissionTags,
                io.github.yysf1949.rag.core.model.PermissionMode permissionMode,
                int topK) {
            searchCalls.incrementAndGet();
            if (toThrow != null) throw toThrow;
            return new ArrayList<>(toReturn);
        }

        @Override
        public void publish(String tenantId, String kbId, long kbVersion) {}

        @Override
        public int deprecate(String tenantId, String kbId, long oldKbVersion) { return 0; }
    }

    static class StubRerank implements RerankService {
        RuntimeException toThrow = null;
        // default: return input unchanged (still truncated to topN)
        @Override
        public List<Chunk> rerank(String query, List<Chunk> candidates, int topN) {
            if (toThrow != null) throw toThrow;
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
    }

    static class StubLlm implements LlmService {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException toThrow = null;
        String toReturn = "LLM answer with [1] citation";

        @Override
        public String generateAnswer(String tenantId, String prompt) {
            calls.incrementAndGet();
            if (toThrow != null) throw toThrow;
            return toReturn;
        }

        @Override
        public String modelId() {
            return "stub-llm";
        }
    }

    static class StubHot implements HotQuestionProvider {
        @Override
        public List<String> recent(String tenantId, int limit) {
            return List.of("怎么退货？", "运费怎么算？");
        }
    }

    static class StubRerankUnavailable extends RerankUnavailableException {
        public StubRerankUnavailable() { super("rerank dead"); }
    }

    static class StubLlmUnavailable extends LlmUnavailableException {
        public StubLlmUnavailable() { super("llm dead"); }
    }

    static class StubEmbeddingUnavailable extends EmbeddingUnavailableException {
        public StubEmbeddingUnavailable() { super("embed dead"); }
    }

    static class StubVectorStoreUnavailable extends VectorStoreUnavailableException {
        public StubVectorStoreUnavailable() { super("vs dead"); }
    }

    // ─── test fixture ─────────────────────────────────────────────────────

    private StubRewrite rewriter;
    private StubAnswerCache answerCache;
    private StubEmbedCache embedCache;
    private StubEmbedGateway embedGateway;
    private StubVectorStore vectorStore;
    private StubRerank reranker;
    private ContextAssembler contextAssembler;
    private StubLlm llm;
    private StubHot hot;
    private QAServiceImpl qa;

    @BeforeEach
    void setUp() {
        rewriter = new StubRewrite();
        answerCache = new StubAnswerCache();
        embedCache = new StubEmbedCache();
        embedGateway = new StubEmbedGateway();
        vectorStore = new StubVectorStore();
        reranker = new StubRerank();
        contextAssembler = new ContextAssembler();
        llm = new StubLlm();
        hot = new StubHot();

        qa = new QAServiceImpl(
                rewriter, answerCache, embedCache, embedGateway,
                vectorStore, reranker, contextAssembler, llm, hot);
    }

    private static Chunk chunk(String id, String section, String content) {
        return new Chunk(id, "tenant-A", "kb-1", "doc-1", "1",
                "退款规则", section, content,
                new HashSet<>(Set.of()), ChunkStatus.ACTIVE, Instant.now(),
                "https://docs.example.com/refund", new float[0]);
    }

    private static Query query(String rawText) {
        return new Query("tenant-A", "user-1", "sess-1", rawText,
                new HashSet<>(Set.of("ROLE_USER")), 20,
                new KbVersion("tenant-A", "kb-1", 1));
    }

    // ─── happy path ───────────────────────────────────────────────────────

    @Test
    void happyPathRunsAllStages() {
        vectorStore.toReturn = List.of(
                chunk("c1", "运费条款", "运费退还"),
                chunk("c2", "质量条款", "质量问题全额退"),
                chunk("c3", "c", "x"));
        Answer a = qa.answer(query("退款运费"));

        assertNotNull(a);
        assertEquals(AnswerSource.LLM, a.source());
        assertEquals("LLM answer with [1] citation", a.finalText());
        assertEquals(3, a.retrieved().size());
        // StubRerank returns min(topN, input) — topN=5, input=3 → 3 reranked.
        assertEquals(3, a.reranked().size());
        assertEquals(1, llm.calls.get());
        assertEquals(1, answerCache.putCalls.get());
    }

    @Test
    void rewriteStepAlwaysRuns() {
        qa.answer(query("test"));
        assertEquals("tenant-A", rewriter.lastTenant);
        assertEquals("test", rewriter.lastText);
    }

    // ─── cache hit ────────────────────────────────────────────────────────

    @Test
    void cacheHitShortCircuitsTheRestOfTheChain() {
        // First call populates the cache via the full chain.
        vectorStore.toReturn = List.of(chunk("c1", "s", "x"));
        Answer first = qa.answer(query("退款"));
        assertEquals(AnswerSource.LLM, first.source());

        // Reset the call counters.
        int llmCallsBefore = llm.calls.get();
        int searchCallsBefore = vectorStore.searchCalls.get();

        // Verify the cache has the expected entry — guards against
        // silent off-by-one in the cache-key derivation.
        String expectedHash = QAServiceImpl.hashQuery("rewritten-text");
        assertNotNull(answerCache.store.get("tenant-A::" + expectedHash),
                "cache must be populated after the first call");

        // Second identical call should hit the cache.
        Answer second = qa.answer(query("退款"));
        assertEquals(AnswerSource.CACHE, second.source(),
                "second call must come from cache, got " + second.source());
        assertEquals(second.finalText(), first.finalText());
        assertEquals(llmCallsBefore, llm.calls.get(),
                "LLM must not be called on cache hit");
        assertEquals(searchCallsBefore, vectorStore.searchCalls.get(),
                "VectorStore must not be queried on cache hit");
    }

    @Test
    void cacheHitKeepsOriginalSourceButFreshLatency() {
        vectorStore.toReturn = List.of(chunk("c1", "s", "x"));
        Answer first = qa.answer(query("退款"));
        Answer second = qa.answer(query("退款"));
        // Latency is freshly measured on each call (cache is a short-circuit,
        // not a replay).
        assertTrue(second.latencyMs() >= 0);
        assertEquals(AnswerSource.CACHE, second.source());
    }

    @Test
    void cacheReadFailureIsTreatedAsMiss() {
        vectorStore.toReturn = List.of(chunk("c1", "s", "x"));
        answerCache.getToThrow = new RuntimeException("redis dead");
        // Should NOT throw — cache failures are silent misses.
        Answer a = qa.answer(query("退款"));
        assertEquals(AnswerSource.LLM, a.source(),
                "cache throw should be caught, LLM should run");
    }

    // ─── rerank failure fallback (spec §7.5) ─────────────────────────────

    @Test
    void rerankFailureFallsBackToTopK() {
        vectorStore.toReturn = List.of(
                chunk("c1", "s1", "x"),
                chunk("c2", "s2", "y"),
                chunk("c3", "s3", "z"));
        reranker.toThrow = new StubRerankUnavailable();

        Answer a = qa.answer(query("退款"));
        // Rerank failed → use TopK truncated to TopN=5 (all 3 fit).
        assertEquals(AnswerSource.LLM, a.source());
        assertEquals(3, a.reranked().size(),
                "rerank failure must fall back to TopK directly");
    }

    @Test
    void rerankFailureTopKTruncatedToTopN() {
        // 10 candidates → rerank fails → use top-5 (truncated to topN).
        List<Chunk> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(chunk("c" + i, "s", "x"));
        vectorStore.toReturn = ten;
        reranker.toThrow = new StubRerankUnavailable();

        Answer a = qa.answer(query("退款"));
        assertEquals(5, a.reranked().size(), "TopN cap = 5 after rerank failure");
    }

    // ─── LLM failure fallback (spec §7.5) ────────────────────────────────

    @Test
    void llmFailureFallsBackToFALLBACK_RULE() {
        vectorStore.toReturn = List.of(
                chunk("c1", "运费条款", "运费退还规则"),
                chunk("c2", "质量条款", "质量问题全额退款"));
        llm.toThrow = new StubLlmUnavailable();

        Answer a = qa.answer(query("运费退吗？"));
        assertEquals(AnswerSource.FALLBACK_RULE, a.source(),
                "LLM throw must downgrade to FALLBACK_RULE");
        // Fallback text contains the chunk content verbatim.
        assertTrue(a.finalText().contains("运费退还规则"),
                "fallback must include retrieved chunk content, got: " + a.finalText());
        assertTrue(a.finalText().contains("质量问题全额退款"));
    }

    @Test
    void llmRuntimeExceptionAlsoFallsBack() {
        // Any RuntimeException — not just LlmUnavailableException — must fall back.
        vectorStore.toReturn = List.of(chunk("c1", "s", "content"));
        llm.toThrow = new IllegalStateException("unexpected llm bug");

        Answer a = qa.answer(query("test"));
        assertEquals(AnswerSource.FALLBACK_RULE, a.source());
    }

    // ─── empty retrieval (spec §7.5) ──────────────────────────────────────

    @Test
    void emptyRetrievalReturnsGracefulFallback() {
        vectorStore.toReturn = List.of(); // zero chunks

        Answer a = qa.answer(query("完全不存在的内容"));
        assertEquals(AnswerSource.FALLBACK_RULE, a.source());
        assertTrue(a.finalText().contains("抱歉"),
                "empty retrieval → graceful 'I don't know' message");
        // Hot questions should appear.
        assertTrue(a.finalText().contains("怎么退货？"));
        assertTrue(a.finalText().contains("运费怎么算？"));
    }

    @Test
    void emptyRetrievalWithoutHotQuestions() {
        vectorStore.toReturn = List.of();
        HotQuestionProvider noHot = new HotQuestionProvider() {
            @Override
            public List<String> recent(String tenantId, int limit) {
                return List.of();
            }
        };
        qa = new QAServiceImpl(
                rewriter, answerCache, embedCache, embedGateway,
                vectorStore, reranker, contextAssembler, llm, noHot);

        Answer a = qa.answer(query("anything"));
        assertEquals(AnswerSource.FALLBACK_RULE, a.source());
        assertTrue(a.finalText().contains("抱歉"));
        assertFalse(a.finalText().contains("您可以试试问"),
                "no hot questions → no suggestion list");
    }

    // ─── vector store failure propagates (spec §10) ───────────────────────

    @Test
    void vectorStoreFailurePropagates() {
        vectorStore.toThrow = new StubVectorStoreUnavailable();
        assertThrows(VectorStoreUnavailableException.class,
                () -> qa.answer(query("test")));
    }

    // ─── embedding failure propagates (spec §10) ──────────────────────────

    @Test
    void embeddingFailurePropagates() {
        embedGateway.toThrow = new StubEmbeddingUnavailable();
        assertThrows(EmbeddingUnavailableException.class,
                () -> qa.answer(query("test")));
    }

    @Test
    void embeddingCacheFailureBypassesCache() {
        vectorStore.toReturn = List.of(chunk("c1", "s", "x"));
        embedCache.toThrowOnGet = new RuntimeException("redis dead");
        // Cache failure must NOT break the chain — gateway is still called.
        Answer a = qa.answer(query("test"));
        assertEquals(AnswerSource.LLM, a.source());
        assertEquals(1, embedGateway.batchCalls.get());
    }

    // ─── embedding cache hit short-circuits gateway ──────────────────────

    @Test
    void embeddingCacheHitBypassesGateway() {
        vectorStore.toReturn = List.of(chunk("c1", "s", "x"));
        // Pre-seed the embedding cache with the hash of "rewritten-text".
        String hash = QAServiceImpl.hashQuery("rewritten-text");
        embedCache.store.put(hash, ZERO_VEC);

        qa.answer(query("test"));
        assertEquals(0, embedGateway.batchCalls.get(),
                "embedding cache hit must bypass the gateway");
    }

    // ─── query rewriting shapes the hash ──────────────────────────────────

    @Test
    void cacheKeyReflectsRewrittenText() {
        vectorStore.toReturn = List.of(chunk("c1", "s", "x"));

        // First call with raw "退款" — rewriter maps it to "rewritten-text".
        qa.answer(query("退款"));
        // The cache should be keyed under "rewritten-text"'s hash.
        String expectedHash = QAServiceImpl.hashQuery("rewritten-text");
        assertNotNull(answerCache.store.get("tenant-A::" + expectedHash),
                "cache entry must use REWRITTEN text hash, not raw");

        // Change the rewriter to a different output for the next call.
        rewriter.toReturn = new RewriteResult("different-rewrite", 0.9, false);
        Answer second = qa.answer(query("退款"));
        // Different rewrite → cache miss → full chain re-runs.
        assertEquals(AnswerSource.LLM, second.source());
        assertEquals(2, vectorStore.searchCalls.get());
    }

    // ─── input validation ─────────────────────────────────────────────────

    @Test
    void nullQueryThrows() {
        assertThrows(NullPointerException.class, () -> qa.answer(null));
    }

    @Test
    void constructorRejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> new QAServiceImpl(null, answerCache, embedCache, embedGateway,
                        vectorStore, reranker, contextAssembler, llm, hot));
        assertThrows(NullPointerException.class,
                () -> new QAServiceImpl(rewriter, null, embedCache, embedGateway,
                        vectorStore, reranker, contextAssembler, llm, hot));
        // ... and so on; we trust that Object.requireNonNull covers the rest.
    }

    // ─── static helpers ───────────────────────────────────────────────────

    @Test
    void hashQueryIsDeterministic() {
        String h1 = QAServiceImpl.hashQuery("退款运费");
        String h2 = QAServiceImpl.hashQuery("退款运费");
        assertEquals(h1, h2);
    }

    @Test
    void hashQueryIgnoresCaseAndWhitespace() {
        String h1 = QAServiceImpl.hashQuery("退款");
        String h2 = QAServiceImpl.hashQuery("退款  ");
        String h3 = QAServiceImpl.hashQuery("退 款");
        assertEquals(h1, h2, "trailing whitespace should be ignored");
        assertNotEquals(h1, h3, "different word boundaries → different hash");
    }

    @Test
    void tagSetFiltersBlanks() {
        Set<String> s = QAServiceImpl.tagSet("a", "", "  ", null, "b");
        assertEquals(2, s.size());
        assertTrue(s.contains("a"));
        assertTrue(s.contains("b"));
    }

    @Test
    void takeHelper() {
        List<Integer> in = List.of(1, 2, 3, 4, 5);
        assertEquals(List.of(1, 2, 3), QAServiceImpl.take(in, 3));
        assertEquals(List.of(1, 2, 3, 4, 5), QAServiceImpl.take(in, 10));
        assertEquals(List.of(), QAServiceImpl.take(List.of(), 5));
        assertEquals(List.of(), QAServiceImpl.take(null, 5));
    }

    @Test
    void fallbackFromChunksIncludesContent() {
        String fb = QAServiceImpl.fallbackFromChunks(
                "运费退吗？",
                List.of(
                        chunk("c1", "运费条款", "运费退还"),
                        chunk("c2", "质量条款", "质量问题全额退")));
        assertTrue(fb.contains("运费退还"));
        assertTrue(fb.contains("质量问题全额退"));
        assertTrue(fb.contains("运费退吗？"));
        assertTrue(fb.contains("[1]"));
        assertTrue(fb.contains("[2]"));
    }
}
