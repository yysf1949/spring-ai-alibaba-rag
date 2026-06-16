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
 * <h2>Bean resolution strategy</h2>
 * <ul>
 *   <li>{@link EmbeddingGateway}, {@link RerankService}, {@link LlmService}
 *       — registered by {@code io.github.yysf1949.rag.embedding.stub.EmbeddingStubConfig}
 *       (the stub beans), guarded by
 *       {@code @ConditionalOnMissingBean} so Phase 5-P4 real
 *       DashScope impls override the stubs transparently.</li>
 *   <li>{@link VectorStore}, {@link AnswerCache}, {@link EmbeddingCache},
 *       {@link HotQuestionProvider} — provided by {@code rag-redis} when
 *       Redis is on the classpath; otherwise the {@link Noop} variants
 *       declared at the bottom of this file are wired. The {@code Noop}
 *       variants are <b>assembly-layer</b> concerns (graceful
 *       degradation when Redis is unavailable), so they live with the
 *       Spring Boot entry point rather than in {@code rag-redis}.</li>
 * </ul>
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
            @Autowired(required = false) HotQuestionProvider hotQuestions,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {

        // Fall back to no-op variants when the real impl is not on the
        // classpath. EmbeddingGateway / RerankService / LlmService are
        // covered by EmbeddingStubConfig — if those beans are missing
        // for any reason, still wire something to keep the app runnable.
        AnswerCache ans = answerCache != null ? answerCache : new NoopAnswerCache();
        EmbeddingCache ec = embeddingCache != null ? embeddingCache : new NoopEmbeddingCache();
        VectorStore vs = vectorStore != null ? vectorStore : new NoopVectorStore();
        EmbeddingGateway eg = embeddingGateway != null ? embeddingGateway : new io.github.yysf1949.rag.embedding.stub.StubEmbeddingGateway();
        RerankService rr = reranker != null ? reranker : new io.github.yysf1949.rag.embedding.stub.StubRerankService();
        LlmService ll = llm != null ? llm : new io.github.yysf1949.rag.embedding.stub.StubLlmService();
        HotQuestionProvider hq = hotQuestions != null ? hotQuestions : new InMemoryHotQuestionProvider();

        // spec §9.1: wire the 10-arg constructor so all rag.qa.* metrics
        // are published to the Spring-managed MeterRegistry (which the
        // actuator /actuator/prometheus endpoint exposes).
        return new QAServiceImpl(rewriter, ans, ec, eg, vs, rr, contextAssembler, ll, hq,
                meterRegistry);
    }

    // ─── no-op fallbacks (assembly-layer degradation only) ──────────────

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
     * No-op vector store — every retrieval is empty. Forces the
     * {@code QAServiceImpl} degradation ladder into the
     * "emptyRetrieval → FALLBACK_RULE" branch, which is exactly what
     * smoke tests want to assert.
     */
    static class NoopVectorStore implements VectorStore {
        @Override
        public int upsert(List<io.github.yysf1949.rag.core.model.Chunk> chunks) { return 0; }
        @Override
        public int deleteByIds(String tenantId, String kbId, long kbVersion, List<String> chunkIds) { return 0; }
        @Override
        public List<io.github.yysf1949.rag.core.model.Chunk> search(
                float[] queryVector,
                String tenantId,
                String kbId,
                long kbVersion,
                List<String> userPermissionTags,
                io.github.yysf1949.rag.core.model.PermissionMode permissionMode,
                int topK) {
            return List.of();
        }
        @Override
        public void publish(String tenantId, String kbId, long kbVersion) { /* no-op */ }
        @Override
        public int deprecate(String tenantId, String kbId, long oldKbVersion) { return 0; }
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