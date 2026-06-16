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
import io.github.yysf1949.rag.core.port.RerankService;
import io.github.yysf1949.rag.core.port.RewriteService;
import io.github.yysf1949.rag.core.port.RewriteService.RewriteResult;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.context.ContextAssembler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Micrometer metric injection — design spec §9.1.
 *
 * <p>Verifies the {@link QAServiceImpl} 10-arg constructor publishes the
 * correct counters, timers, and degradation events to the supplied
 * {@link MeterRegistry}.</p>
 *
 * <p>Test doubles are local copies of the ones in {@code QAServiceImplTest}
 * (kept private here so the metrics assertions don't depend on the
 * behaviour-test file's internals).</p>
 */
class QAServiceImplMetricsTest {

    private static final float[] ZERO_VEC = new float[16];

    private StubRewrite rewriter;
    private StubAnswerCache answerCache;
    private StubEmbedCache embedCache;
    private StubEmbedGateway embedGateway;
    private StubVectorStore vectorStore;
    private StubRerank reranker;
    private ContextAssembler contextAssembler;
    private StubLlm llm;
    private HotQuestionProvider hot;
    private MeterRegistry meterRegistry;
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
        hot = (tenantId, limit) -> List.of("hot1", "hot2");
        meterRegistry = new SimpleMeterRegistry();
        qa = new QAServiceImpl(
                rewriter, answerCache, embedCache, embedGateway,
                vectorStore, reranker, contextAssembler, llm, hot,
                meterRegistry);
    }

    @Test
    void happyPathIncrementsRequestsCounter() {
        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        qa.answer(query("退款"));

        double count = meterRegistry.counter("rag.qa.requests.total",
                "tenant", "tenant-A",
                "source", AnswerSource.LLM.name()).count();
        assertEquals(1.0, count, 0.0001,
                "rag.qa.requests.total{tenant,source=LLM} must be 1 after one LLM call");
    }

    @Test
    void cacheHitIncrementsCacheSourcedCounter() {
        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        qa.answer(query("退款"));
        qa.answer(query("退款"));

        double cacheHits = meterRegistry.counter("rag.qa.requests.total",
                "tenant", "tenant-A",
                "source", AnswerSource.CACHE.name()).count();
        assertEquals(1.0, cacheHits, 0.0001,
                "second call should publish source=CACHE counter, got " + cacheHits);
    }

    @Test
    void eachStageTimerIsRegistered() {
        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        qa.answer(query("退款"));

        for (String stage : List.of("rewrite", "cacheCheck", "embed",
                "retrieve", "rerank", "assemble", "generate")) {
            var timer = meterRegistry.find("rag.qa.latency.ms")
                    .tag("stage", stage)
                    .timer();
            assertNotNull(timer, "rag.qa.latency.ms{stage=" + stage + "} must be registered");
            assertTrue(timer.count() >= 1,
                    "rag.qa.latency.ms{stage=" + stage + "} must record ≥1 sample, got " + timer.count());
        }
    }

    @Test
    void rerankFailureIncrementsDegradationCounter() {
        vectorStore.toReturn = List.of(
                chunk("c1", "s1", "x"),
                chunk("c2", "s2", "y"));
        reranker.toThrow = new RerankUnavailableException("rerank dead");
        qa.answer(query("退款"));

        double rerankDeg = meterRegistry.counter("rag.qa.degradation.total",
                "tenant", "tenant-A",
                "reason", "rerank_unavailable").count();
        assertEquals(1.0, rerankDeg, 0.0001,
                "rerank failure must increment rag.qa.degradation.total{reason=rerank_unavailable}");
    }

    @Test
    void llmFailureIncrementsDegradationCounter() {
        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        llm.toThrow = new LlmUnavailableException("llm dead");
        qa.answer(query("退款"));

        double llmDeg = meterRegistry.counter("rag.qa.degradation.total",
                "tenant", "tenant-A",
                "reason", "llm_unavailable").count();
        assertEquals(1.0, llmDeg, 0.0001,
                "LLM failure must increment rag.qa.degradation.total{reason=llm_unavailable}");
    }

    @Test
    void noOpConstructorStillWorks() {
        QAServiceImpl legacy = new QAServiceImpl(
                rewriter, answerCache, embedCache, embedGateway,
                vectorStore, reranker, contextAssembler, llm, hot);
        vectorStore.toReturn = List.of(chunk("c1", "s1", "x"));
        Answer a = legacy.answer(query("退款"));
        assertEquals(AnswerSource.LLM, a.source());
    }

    // ─── test doubles (local copies) ─────────────────────────────────────

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

    static class StubRewrite implements RewriteService {
        @Override
        public RewriteResult rewrite(String tenantId, String rawText) {
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
        @Override public List<float[]> embedBatch(List<String> texts) {
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
        @Override public int upsert(List<Chunk> chunks) { return chunks.size(); }
        @Override public int deleteByIds(String tenantId, String kbId, long kbVersion, List<String> chunkIds) { return 0; }
        @Override public List<Chunk> search(float[] queryVector, String tenantId, String kbId, long kbVersion,
                                            List<String> userPermissionTags,
                                            io.github.yysf1949.rag.core.model.PermissionMode permissionMode,
                                            int topK) {
            return new ArrayList<>(toReturn);
        }
        @Override public void publish(String tenantId, String kbId, long kbVersion) { }
        @Override public int deprecate(String tenantId, String kbId, long oldKbVersion) { return 0; }
    }

    static class StubRerank implements RerankService {
        RuntimeException toThrow = null;
        @Override public List<Chunk> rerank(String query, List<Chunk> candidates, int topN) {
            if (toThrow != null) throw toThrow;
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
    }

    static class StubLlm implements LlmService {
        RuntimeException toThrow = null;
        AtomicInteger calls = new AtomicInteger();
        @Override public String generateAnswer(String tenantId, String prompt) {
            calls.incrementAndGet();
            if (toThrow != null) throw toThrow;
            return "stub-llm-answer";
        }
        @Override public String modelId() { return "stub-llm"; }
    }
}
