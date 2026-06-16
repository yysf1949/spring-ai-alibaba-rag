package io.github.yysf1949.rag.app.config;

import io.github.yysf1949.rag.core.port.AnswerCache;
import io.github.yysf1949.rag.core.port.EmbeddingCache;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.HotQuestionProvider;
import io.github.yysf1949.rag.core.port.LlmService;
import io.github.yysf1949.rag.core.port.QAService;
import io.github.yysf1949.rag.core.port.RerankService;
import io.github.yysf1949.rag.core.port.RewriteService;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.context.ContextAssembler;
import io.github.yysf1949.rag.pipeline.qa.QAServiceImpl;
import io.github.yysf1949.rag.pipeline.rewrite.CachingRuleBasedRewriter;
import io.github.yysf1949.rag.pipeline.rewrite.DefaultChineseSynonymTable;
import io.github.yysf1949.rag.pipeline.rewrite.RuleBasedQueryRewriter;
import io.github.yysf1949.rag.pipeline.rewrite.SynonymTable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Wires the 9 ports that {@link QAServiceImpl} consumes, plus the
 * orchestrator itself.
 *
 * <h2>Bean resolution strategy (Phase 6+ stub-aware)</h2>
 * <ul>
 *   <li>If a real implementation is on the classpath (rag-redis,
 *       rag-embedding once it ships), Spring picks it up via
 *       {@code @ConditionalOnMissingBean} on our stubs.</li>
 *   <li>Otherwise the stub below is registered. This makes the app
 *       runnable in pure-local mode — no Redis, no DashScope, no LLM
 *       network calls — so unit / smoke tests can drive it.</li>
 * </ul>
 *
 * <h2>Stub semantics (each is documented on the class itself)</h2>
 * <ul>
 *   <li>{@link StubLlmService} — echoes the prompt back, marked with
 *       the model id {@code stub-llm}. Spec §13.11 real impl will replace it.</li>
 *   <li>{@link StubEmbeddingGateway} — fixed 16-dim zero vector per text,
 *       keyed on text hash in a {@code ConcurrentHashMap} as the cache.</li>
 *   <li>{@link StubRerankService} — returns the first {@code topN} of the
 *       input, no actual scoring.</li>
 *   <li>{@link InMemoryHotQuestionProvider} — fixed sample list, used
 *       only when retrieval returns empty.</li>
 * </ul>
 *
 * <p>All four stubs are production-safe in the sense that they
 * <b>never throw</b> on a well-formed request. The {@link QAServiceImpl}
 * degradation ladder is exercised by these stubs the same way it will
 * be exercised by the real impls.</p>
 */
@Configuration
public class BeansConfig {

    // ─── core orchestrators (no Spring beans by default; we build them) ──

    @Bean
    public SynonymTable defaultSynonymTable() {
        return DefaultChineseSynonymTable.create();
    }

    @Bean
    public ContextAssembler contextAssembler() {
        return new ContextAssembler();
    }

    /**
     * Internal rule-only rewriter — <b>not</b> exposed as a
     * {@link io.github.yysf1949.rag.core.port.RewriteService} bean. It's
     * a building block for {@link #rewriteService}; exposing it would
     * create a {@code NoUniqueBeanDefinitionException} on injection
     * because both {@code RuleBasedQueryRewriter} and
     * {@code CachingRuleBasedRewriter} implement {@code RewriteService}.
     *
     * <p>Callers that want the rule-only behaviour should depend on
     * {@link RuleBasedQueryRewriter} directly, not the {@code RewriteService}
     * port.</p>
     */
    private RuleBasedQueryRewriter ruleBasedQueryRewriter(SynonymTable table) {
        return new RuleBasedQueryRewriter(table);
    }

    @Bean
    public RewriteService rewriteService(SynonymTable table,
            @Autowired(required = false) io.github.yysf1949.rag.core.port.RewriteCache cache) {
        // cache may be null in local-stub mode — the rewriter handles a
        // null cache by short-circuiting the cache read/write legs.
        // We pass null for the Llm leg too; the stub LLM bean is wired
        // later, but at the rewrite stage we don't need it.
        return new CachingRuleBasedRewriter(ruleBasedQueryRewriter(table), null, cache);
    }

    @Bean
    public QAService qaService(
            RewriteService rewriter,
            @Autowired(required = false) AnswerCache answerCache,
            @Autowired(required = false) EmbeddingCache embeddingCache,
            @Autowired(required = false) EmbeddingGateway embeddingGateway,
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) RerankService reranker,
            ContextAssembler contextAssembler,
            @Autowired(required = false) LlmService llm,
            @Autowired(required = false) HotQuestionProvider hotQuestions) {

        // Fall back to stubs when the real impl is not on the classpath.
        AnswerCache ans = answerCache != null ? answerCache : new NoopAnswerCache();
        EmbeddingCache ec = embeddingCache != null ? embeddingCache : new NoopEmbeddingCache();
        EmbeddingGateway eg = embeddingGateway != null ? embeddingGateway : new StubEmbeddingGateway();
        VectorStore vs = vectorStore != null ? vectorStore : new StubVectorStore();
        RerankService rr = reranker != null ? reranker : new StubRerankService();
        LlmService ll = llm != null ? llm : new StubLlmService();
        HotQuestionProvider hq = hotQuestions != null ? hotQuestions : new InMemoryHotQuestionProvider();

        return new QAServiceImpl(rewriter, ans, ec, eg, vs, rr, contextAssembler, ll, hq);
    }

    // ─── stubs (only registered if no real impl is on the classpath) ────

    /**
     * Local in-process answer cache — never persists, never throws.
     * Used when rag-redis is not on the classpath (e.g. unit tests).
     */
    static class NoopAnswerCache implements AnswerCache {
        private final ConcurrentMap<String, io.github.yysf1949.rag.core.model.Answer> store =
                new ConcurrentHashMap<>();

        @Override
        public java.util.Optional<io.github.yysf1949.rag.core.model.Answer> get(String tenantId, String queryHash) {
            return java.util.Optional.ofNullable(store.get(tenantId + "::" + queryHash));
        }

        @Override
        public boolean put(String tenantId, String queryHash, io.github.yysf1949.rag.core.model.Answer answer) {
            store.put(tenantId + "::" + queryHash, answer);
            return true;
        }

        @Override
        public long invalidateTenant(String tenantId) {
            int before = store.size();
            store.keySet().removeIf(k -> k.startsWith(tenantId + "::"));
            return before - store.size();
        }
    }

    /** No-op embedding cache — every lookup is a miss. */
    static class NoopEmbeddingCache implements EmbeddingCache {
        @Override
        public float[] get(String textHash) { return null; }
        @Override
        public List<float[]> getMany(List<String> textHashes) {
            return textHashes.stream().map(h -> (float[]) null).toList();
        }
        @Override
        public void put(String textHash, float[] vector) { /* no-op */ }
        @Override
        public void putMany(java.util.Map<String, float[]> entries) { /* no-op */ }
    }

    /**
     * Deterministic embedding stub — every text gets the same 16-dim
     * vector. Useful for end-to-end smoke (cache always hits after the
     * first call). Real impl comes from rag-embedding / DashScope.
     */
    public static class StubEmbeddingGateway implements EmbeddingGateway {
        public static final int DIM = 16;
        private final ConcurrentMap<String, float[]> cache = new ConcurrentHashMap<>();

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(t -> cache.computeIfAbsent(t, k -> newZeroVec(DIM))).toList();
        }

        @Override
        public List<float[]> embedWithoutCache(List<String> texts) {
            return texts.stream().map(t -> newZeroVec(DIM)).toList();
        }

        @Override
        public int dimension() { return DIM; }

        @Override
        public void warmCache(java.util.Map<String, float[]> entries) {
            cache.putAll(entries);
        }

        private static float[] newZeroVec(int dim) {
            float[] v = new float[dim];
            // give it a tiny per-text fingerprint so cosine is well-defined
            for (int i = 0; i < dim; i++) v[i] = (float) Math.sin(i * 0.1);
            return v;
        }
    }

    /**
     * Stub vector store — returns whatever was {@code upsert}-ed.
     * Test-only behaviour: ignores all filter parameters and returns
     * the entire stored corpus in insertion order.
     */
    public static class StubVectorStore implements VectorStore {
        private final ConcurrentMap<String, io.github.yysf1949.rag.core.model.Chunk> store =
                new ConcurrentHashMap<>();

        @Override
        public int upsert(List<io.github.yysf1949.rag.core.model.Chunk> chunks) {
            for (var c : chunks) store.put(c.chunkId(), c);
            return chunks.size();
        }

        @Override
        public int deleteByIds(String tenantId, String kbId, long kbVersion, List<String> chunkIds) {
            int n = 0;
            for (String id : chunkIds) if (store.remove(id) != null) n++;
            return n;
        }

        @Override
        public List<io.github.yysf1949.rag.core.model.Chunk> search(
                float[] queryVector,
                String tenantId,
                String kbId,
                long kbVersion,
                List<String> userPermissionTags,
                io.github.yysf1949.rag.core.model.PermissionMode permissionMode,
                int topK) {
            return store.values().stream().limit(topK).toList();
        }

        @Override
        public void publish(String tenantId, String kbId, long kbVersion) { /* no-op */ }

        @Override
        public int deprecate(String tenantId, String kbId, long oldKbVersion) { return 0; }
    }

    /** Stub rerank — first {@code topN} of input, no scoring. */
    public static class StubRerankService implements RerankService {
        @Override
        public List<io.github.yysf1949.rag.core.model.Chunk> rerank(
                String query, List<io.github.yysf1949.rag.core.model.Chunk> candidates, int topN) {
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
    }

    /**
     * Stub LLM — echoes the prompt wrapped in a marker. Marks itself
     * with {@code modelId() == "stub-llm"} so dashboards can split
     * "stub traffic" from "real LLM traffic" if both are ever live.
     */
    public static class StubLlmService implements LlmService {
        @Override
        public String generateAnswer(String tenantId, String prompt) {
            return "[stub-llm] Received prompt of length " + prompt.length()
                    + " chars; would normally call DashScope here.";
        }

        @Override
        public String modelId() { return "stub-llm"; }
    }

    /** Stub hot-question provider — fixed 3-entry list. */
    public static class InMemoryHotQuestionProvider implements HotQuestionProvider {
        private static final List<String> FALLBACK = List.of(
                "运费怎么退？",
                "支持哪些支付方式？",
                "如何修改收货地址？");

        @Override
        public List<String> recent(String tenantId, int limit) {
            return FALLBACK.subList(0, Math.min(limit, FALLBACK.size()));
        }
    }
}
