package io.github.yysf1949.rag.pipeline.qa;

import io.github.yysf1949.rag.core.exception.LlmUnavailableException;
import io.github.yysf1949.rag.core.exception.RerankUnavailableException;
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
import io.github.yysf1949.rag.core.port.QAService;
import io.github.yysf1949.rag.core.port.RerankResult;
import io.github.yysf1949.rag.core.port.RerankService;
import io.github.yysf1949.rag.core.port.RewriteService;
import io.github.yysf1949.rag.core.port.RewriteService.RewriteResult;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.context.ContextAssembler;
import io.github.yysf1949.rag.pipeline.logging.PipelineMdc;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MDC (Mapped Diagnostic Context) instrumentation — design spec §9.2.
 *
 * <p>Every log line emitted during an {@link QAServiceImpl#answer} call
 * must carry {@code tenant}, {@code requestId} (already set by
 * {@code MdcTenantFilter}), {@code queryHash} (per-request), and a
 * {@code stage} that flips as the 8-step chain progresses.</p>
 *
 * <p>We capture the MDC state by inserting {@code runnable}-spies into
 * the stub rewriter / llm — those run inside the
 * {@code try { MDC.put("stage", ...); ... }} blocks of QAServiceImpl,
 * so they observe the MDC mid-flight.</p>
 *
 * <p>The test double classes are copies from {@code QAServiceImplTest} —
 * local so we can wire MDC-spy hooks without polluting the main test
 * file.</p>
 */
class QAServiceImplMdcTest {

    private static final float[] ZERO_VEC = new float[16];

    private SpyRewrite rewriter;
    private StubAnswerCache answerCache;
    private StubEmbedCache embedCache;
    private StubEmbedGateway embedGateway;
    private StubVectorStore vectorStore;
    private SpyRerank reranker;
    private ContextAssembler contextAssembler;
    private SpyLlm llm;
    private HotQuestionProvider hot;
    private QAServiceImpl qa;

    @BeforeEach
    void setUp() {
        // Simulate the HTTP filter setting tenant + requestId at the boundary.
        MDC.put(PipelineMdc.KEY_TENANT, "tenant-A");
        MDC.put(PipelineMdc.KEY_REQUEST_ID, "req-test-123");

        rewriter = new SpyRewrite();
        answerCache = new StubAnswerCache();
        embedCache = new StubEmbedCache();
        embedGateway = new StubEmbedGateway();
        vectorStore = new StubVectorStore();
        reranker = new SpyRerank();
        contextAssembler = new ContextAssembler();
        llm = new SpyLlm();
        hot = (tenantId, limit) -> List.of("hot1", "hot2");
        qa = new QAServiceImpl(
                rewriter, answerCache, embedCache, embedGateway,
                vectorStore, reranker, contextAssembler, llm, hot,
                new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void queryHashPinnedForTheWholeRequest_andClearedAfter() {
        AtomicReference<String> seenQueryHash = new AtomicReference<>();
        rewriter.captureMdcAtRewrite = mdc -> seenQueryHash.set(mdc.get(PipelineMdc.KEY_QUERY_HASH));

        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        qa.answer(query("退款"));

        String pinned = seenQueryHash.get();
        assertNotNull(pinned, "queryHash must be visible to rewrite-stage loggers");
        assertEquals(64, pinned.length(), "SHA-256 hex must be 64 chars, got '" + pinned + "'");
        // queryHash must be CLEARED after answer() returns — otherwise the
        // next request on this thread inherits the previous request's hash.
        assertNull(MDC.get(PipelineMdc.KEY_QUERY_HASH),
                "queryHash must be cleared after answer() returns; saw " + MDC.get(PipelineMdc.KEY_QUERY_HASH));
    }

    @Test
    void stageFlipsAcrossStages() {
        // Capture the stage MDC value at every stub hook (rewrite / embed
        // / retrieve / rerank / generate) so we can assert the chain order.
        List<String> stages = new ArrayList<>();
        rewriter.captureMdcAtRewrite = mdc -> stages.add(mdc.get(PipelineMdc.KEY_STAGE));
        embedGateway.captureMdc = mdc -> stages.add(mdc.get(PipelineMdc.KEY_STAGE));
        vectorStore.captureMdc = mdc -> stages.add(mdc.get(PipelineMdc.KEY_STAGE));
        reranker.captureMdc = mdc -> stages.add(mdc.get(PipelineMdc.KEY_STAGE));
        llm.captureMdc = mdc -> stages.add(mdc.get(PipelineMdc.KEY_STAGE));

        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        qa.answer(query("退款"));

        assertEquals(
                List.of("rewrite", "embed", "retrieve", "rerank", "generate"),
                stages,
                "stage MDC must flip in chain order, got " + stages);
    }

    @Test
    void stageClearedAfterException() {
        // Make rerank throw — verify MDC "stage" is still cleared even on
        // exception (the cache HIT / rerank fallback branches were the
        // early-return paths that historically leaked MDC state).
        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        reranker.toThrow = new RerankUnavailableException("rerank dead");
        qa.answer(query("退款"));  // rerank fallback path returns READY
        assertNull(MDC.get(PipelineMdc.KEY_STAGE),
                "stage must be cleared even when rerank throws — got " + MDC.get(PipelineMdc.KEY_STAGE));
    }

    @Test
    void stageClearedAfterLlmException() {
        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        llm.toThrow = new LlmUnavailableException("llm dead");
        qa.answer(query("退款"));
        assertNull(MDC.get(PipelineMdc.KEY_STAGE),
                "stage must be cleared even when LLM throws");
        assertNull(MDC.get(PipelineMdc.KEY_QUERY_HASH),
                "queryHash must be cleared even when LLM throws");
    }

    @Test
    void stageClearedOnCacheHitEarlyReturn() {
        // First call populates the cache.
        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        qa.answer(query("退款"));
        // Second call hits the cache and short-circuits before embed.
        qa.answer(query("退款"));
        assertNull(MDC.get(PipelineMdc.KEY_STAGE),
                "cache HIT early-return must still clear stage MDC");
        assertNull(MDC.get(PipelineMdc.KEY_QUERY_HASH),
                "cache HIT early-return must still clear queryHash MDC");
    }

    @Test
    void tenantAndRequestIdPreservedFromFilter() {
        // The filter sets tenant + requestId at the HTTP boundary; the
        // pipeline must NOT stomp on them — only ADD its own keys.
        AtomicReference<Map<String, String>> rewriterMdc = new AtomicReference<>();
        rewriter.captureMdcAtRewrite = mdc -> rewriterMdc.set(mdc);
        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        qa.answer(query("退款"));

        Map<String, String> seen = rewriterMdc.get();
        assertNotNull(seen);
        assertEquals("tenant-A", seen.get(PipelineMdc.KEY_TENANT),
                "tenant from MdcTenantFilter must be preserved");
        assertEquals("req-test-123", seen.get(PipelineMdc.KEY_REQUEST_ID),
                "requestId from MdcTenantFilter must be preserved");
        assertEquals("rewrite", seen.get(PipelineMdc.KEY_STAGE));
        assertNotNull(seen.get(PipelineMdc.KEY_QUERY_HASH),
                "queryHash must be added by the pipeline");
    }

    @Test
    void cacheHitBranchDoesNotEmitRerankOrGenerateStage() {
        // Warm the cache.
        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        qa.answer(query("退款"));

        // Second call — cache HIT, only cacheCheck stage should appear.
        List<String> stages = new ArrayList<>();
        rewriter.captureMdcAtRewrite = mdc -> stages.add(mdc.get(PipelineMdc.KEY_STAGE));
        embedGateway.captureMdc = mdc -> stages.add(mdc.get(PipelineMdc.KEY_STAGE));
        llm.captureMdc = mdc -> stages.add(mdc.get(PipelineMdc.KEY_STAGE));
        qa.answer(query("退款"));

        // rewriter still runs (it's stage 1, before cacheCheck) so we expect
        // exactly one "rewrite" entry. embed and llm hooks must NOT fire
        // on a cache hit.
        assertEquals(List.of("rewrite"), stages,
                "cache HIT should only run rewrite stage, got " + stages);
    }

    @Test
    void sanityNoFalsePass() {
        // If any of the assertions above were silently skipped (e.g. a stub
        // was never invoked) this sanity test would still pass, but it
        // documents that vector store / llm wiring actually runs the stubs
        // — useful when the test file is read in isolation.
        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        Answer a = qa.answer(query("退款"));
        assertEquals(AnswerSource.LLM, a.source());
        assertFalse(MDC.getCopyOfContextMap() != null
                && MDC.getCopyOfContextMap().containsKey(PipelineMdc.KEY_QUERY_HASH),
                "MDC must be cleaned by the time answer() returns");
        assertTrue(true);
    }

    // ─── test doubles ─────────────────────────────────────────────────────

    private static Chunk chunk(String id, String section, String content) {
        return new Chunk(id, "tenant-A", "kb-1", "doc-1", "1",
                "退款规则", section, content,
                new HashSet<>(Set.of()), ChunkStatus.ACTIVE, Instant.now(),
                "https://docs.example.com/refund", new float[0], null);
    }

    private static Query query(String rawText) {
        return new Query("tenant-A", "user-1", "sess-1", rawText,
                new HashSet<>(Set.of("ROLE_USER")), 20,
                new KbVersion("tenant-A", "kb-1", 1));
    }

    /** Spy — invokes the supplied MDC-snapshot callback while rewrite runs. */
    static class SpyRewrite implements RewriteService {
        java.util.function.Consumer<Map<String, String>> captureMdcAtRewrite;
        @Override
        public RewriteResult rewrite(String tenantId, String rawText) {
            if (captureMdcAtRewrite != null) {
                captureMdcAtRewrite.accept(MDC.getCopyOfContextMap());
            }
            return new RewriteResult("rewritten-text", 0.9, false);
        }
    }

    static class StubAnswerCache implements AnswerCache {
        final ConcurrentHashMap<String, Answer> store = new ConcurrentHashMap<>();
        @Override public Optional<Answer> get(String tenantId, String queryHash) {
            return Optional.ofNullable(store.get(tenantId + "::" + queryHash));
        }
        @Override public boolean put(String tenantId, String queryHash, Answer answer) {
            store.put(tenantId + "::" + queryHash, answer);
            return true;
        }
        @Override public long invalidateTenant(String tenantId) { return 0; }
        @Override public double hitRatio(String tenantId) { return 0.0; }
    }

    static class StubEmbedCache implements EmbeddingCache {
        @Override public float[] get(String textHash) { return null; }
        @Override public List<float[]> getMany(List<String> textHashes) { return List.of(); }
        @Override public void put(String textHash, float[] vector) { }
        @Override public void putMany(Map<String, float[]> entries) { }
    }

    static class StubEmbedGateway implements EmbeddingGateway {
        java.util.function.Consumer<Map<String, String>> captureMdc;
        @Override public List<float[]> embedBatch(List<String> texts) {
            if (captureMdc != null) captureMdc.accept(MDC.getCopyOfContextMap());
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) out.add(ZERO_VEC.clone());
            return out;
        }
        @Override public List<float[]> embedWithoutCache(List<String> texts) { return embedBatch(texts); }
        @Override public int dimension() { return 16; }
        @Override public void warmCache(Map<String, float[]> entries) { }
    }

    static class StubVectorStore implements VectorStore {
        List<Chunk> toReturn = new ArrayList<>();
        java.util.function.Consumer<Map<String, String>> captureMdc;
        @Override public int upsert(List<Chunk> chunks) { return chunks.size(); }
        @Override public int deleteByIds(String tenantId, String kbId, long kbVersion, List<String> chunkIds) { return 0; }
        @Override public List<Chunk> search(float[] queryVector, String tenantId, String kbId, long kbVersion,
                                            List<String> userPermissionTags,
                                            io.github.yysf1949.rag.core.model.PermissionMode permissionMode,
                                            int topK) {
            if (captureMdc != null) captureMdc.accept(MDC.getCopyOfContextMap());
            return new ArrayList<>(toReturn);
        }
        @Override public void publish(String tenantId, String kbId, long kbVersion) { }
        @Override public int deprecate(String tenantId, String kbId, long oldKbVersion) { return 0; }
        @Override public int deleteByDocumentId(String tenantId, String kbId, String documentId, long kbVersion) { return 0; }
    }

    static class SpyRerank implements RerankService {
        java.util.function.Consumer<Map<String, String>> captureMdc;
        RuntimeException toThrow = null;
        @Override public List<Chunk> rerank(String query, List<Chunk> candidates, int topN) {
            if (captureMdc != null) captureMdc.accept(MDC.getCopyOfContextMap());
            if (toThrow != null) throw toThrow;
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
        @Override public List<RerankResult> rerankWithScores(String query, List<Chunk> candidates, int topN) {
            // Capture MDC — this is now the primary rerank call path.
            if (captureMdc != null) captureMdc.accept(MDC.getCopyOfContextMap());
            if (toThrow != null) throw toThrow;
            List<Chunk> chunks = candidates.subList(0, Math.min(topN, candidates.size()));
            return chunks.stream()
                    .map(c -> new RerankResult(c, 0.0))
                    .toList();
        }
    }

    static class SpyLlm implements LlmService {
        java.util.function.Consumer<Map<String, String>> captureMdc;
        RuntimeException toThrow = null;
        @Override public String generateAnswer(String tenantId, String prompt) {
            if (captureMdc != null) captureMdc.accept(MDC.getCopyOfContextMap());
            if (toThrow != null) throw toThrow;
            return "stub-llm-answer";
        }
        @Override public String modelId() { return "stub-llm"; }
    }
}