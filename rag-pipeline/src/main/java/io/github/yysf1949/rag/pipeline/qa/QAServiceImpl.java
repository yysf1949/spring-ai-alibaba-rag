package io.github.yysf1949.rag.pipeline.qa;

import io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException;
import io.github.yysf1949.rag.core.exception.LlmUnavailableException;
import io.github.yysf1949.rag.core.exception.RerankUnavailableException;
import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.Citation;
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
import io.github.yysf1949.rag.pipeline.context.ContextAssembler.AssembledPrompt;
import io.github.yysf1949.rag.pipeline.logging.PipelineMdc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link QAService} implementation — design spec §7.1 + §13.11.
 *
 * <h2>The 8-step chain</h2>
 * <ol>
 *   <li>{@link #rewrite} — {@link RewriteService}</li>
 *   <li>{@link #checkCache} — {@link AnswerCache} (early-out on hit)</li>
 *   <li>{@link #embed} — {@link EmbeddingCache} then {@link EmbeddingGateway}</li>
 *   <li>{@link #retrieve} — {@link VectorStore#search}</li>
 *   <li>{@link #rerank} — {@link RerankService}, with rerank-failure fallback</li>
 *   <li>{@link #assemble} — {@link ContextAssembler}</li>
 *   <li>{@link #generate} — {@link LlmService}, with LLM-failure fallback</li>
 *   <li>{@link #cachePut} — best-effort {@link AnswerCache#put}</li>
 * </ol>
 *
 * <h2>Degradation ladder (spec §7.5)</h2>
 * Each step has a defined "what if it fails" branch — see the method
 * javadocs. The defaults match spec §7.5 to the letter; the {@code source}
 * field on the returned {@link Answer} records which leg actually produced
 * the text, so downstream metrics can split dashboards by leg.
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>{@link VectorStoreUnavailableException}</b> — propagated. The HTTP
 *       layer translates this to 503 + Retry-After (spec §10).</li>
 *   <li><b>{@link EmbeddingUnavailableException}</b> — propagated. Same
 *       treatment (embedding gateway is the upstream dependency for the
 *       whole retrieval leg).</li>
 *   <li><b>{@link RerankUnavailableException}</b> — caught, log warn, skip
 *       rerank, continue with vector TopK (truncated to TopN=5).</li>
 *   <li><b>{@link LlmUnavailableException}</b> — caught, log warn, return
 *       FALLBACK_RULE (concatenated retrieved chunks).</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * Stateless after construction — safe to call from a thread pool.
 */
public class QAServiceImpl implements QAService {

    private static final Logger log = LoggerFactory.getLogger(QAServiceImpl.class);

    /** Spec §7.1 / §8.1 — default rerank depth. */
    public static final int DEFAULT_TOP_K = 20;
    /** Spec §7.1 / §8.1 — default final-chunk count. */
    public static final int DEFAULT_TOP_N = 5;

    private final RewriteService rewriter;
    private final AnswerCache answerCache;
    private final EmbeddingCache embeddingCache;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;
    private final RerankService reranker;
    private final ContextAssembler contextAssembler;
    private final LlmService llm;
    private final HotQuestionProvider hotQuestions;
    private final MeterRegistry meterRegistry;

    /**
     * Stage timer names — spec §9.1 metric {@code rag.qa.latency.ms{stage}}.
     * Each stage records its wall-clock duration as a Micrometer Timer.
     */
    private static final List<String> STAGE_NAMES = List.of(
            "rewrite", "cacheCheck", "embed", "retrieve",
            "rerank", "assemble", "generate");

    public QAServiceImpl(RewriteService rewriter,
                         AnswerCache answerCache,
                         EmbeddingCache embeddingCache,
                         EmbeddingGateway embeddingGateway,
                         VectorStore vectorStore,
                         RerankService reranker,
                         ContextAssembler contextAssembler,
                         LlmService llm,
                         HotQuestionProvider hotQuestions) {
        this(rewriter, answerCache, embeddingCache, embeddingGateway,
                vectorStore, reranker, contextAssembler, llm, hotQuestions,
                new SimpleMeterRegistry());
    }

    public QAServiceImpl(RewriteService rewriter,
                         AnswerCache answerCache,
                         EmbeddingCache embeddingCache,
                         EmbeddingGateway embeddingGateway,
                         VectorStore vectorStore,
                         RerankService reranker,
                         ContextAssembler contextAssembler,
                         LlmService llm,
                         HotQuestionProvider hotQuestions,
                         MeterRegistry meterRegistry) {
        this.rewriter = Objects.requireNonNull(rewriter, "rewriter");
        this.answerCache = Objects.requireNonNull(answerCache, "answerCache");
        this.embeddingCache = Objects.requireNonNull(embeddingCache, "embeddingCache");
        this.embeddingGateway = Objects.requireNonNull(embeddingGateway, "embeddingGateway");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.reranker = Objects.requireNonNull(reranker, "reranker");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
        this.llm = Objects.requireNonNull(llm, "llm");
        this.hotQuestions = Objects.requireNonNull(hotQuestions, "hotQuestions");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    @Override
    public Answer answer(Query query) {
        Objects.requireNonNull(query, "query");
        long t0 = System.currentTimeMillis();
        // Per-stage timing — maps to spec §9.1 metric `rag.qa.latency.ms{stage}`.
        long[] stamps = new long[8];
        // Per-stage Timer references, resolved once per request (Micrometer
        // meters are bound to the registry, which is the heavy side of
        // Counter.builder.register — caching the Timer per stage avoids a
        // hash lookup on every answer() call).
        Map<String, Timer> stageTimers = buildStageTimers(query.tenantId());
        // Pin queryHash on the MDC for the WHOLE request — every log line
        // emitted during answer() now carries it. Cleared at the end via the
        // outer try/finally.
        PipelineMdc.put(PipelineMdc.KEY_QUERY_HASH, hashQuery(query.rawText()));

        try {
            return answerInternal(query, t0, stamps, stageTimers);
        } finally {
            // Spec §9.1 — expose cache hit ratios as Micrometer Gauges (best-effort)
            try {
                meterRegistry.gauge(
                        "rag.qa.cache.hit.ratio",
                        Tags.of("tenant", query.tenantId()),
                        answerCache,
                        ac -> ac.hitRatio(query.tenantId()));
            } catch (RuntimeException e) {
                log.debug("Failed to register rag.qa.cache.hit.ratio gauge: {}", e.getMessage());
            }
            try {
                meterRegistry.gauge(
                        "rag.embedding.cache.hit.ratio",
                        embeddingCache,
                        EmbeddingCache::hitRatio);
            } catch (RuntimeException e) {
                log.debug("Failed to register rag.embedding.cache.hit.ratio gauge: {}", e.getMessage());
            }
            // Spec §9.2: every per-request MDC key we own must be cleared
            // before the response returns, so the next request on this
            // thread starts with a clean slate.
            MDC.remove(PipelineMdc.KEY_QUERY_HASH);
            MDC.remove(PipelineMdc.KEY_STAGE);
        }
    }

    /**
     * The 8-step chain. Split out from {@link #answer} so the outer MDC
     * cleanup (queryHash) wraps every return / exception path with a
     * single try/finally — see spec §9.2.
     */
    private Answer answerInternal(Query query, long t0, long[] stamps,
                                  Map<String, Timer> stageTimers) {
        // Step 1: rewrite
        RewriteResult rewritten;
        try {
            PipelineMdc.put(PipelineMdc.KEY_STAGE, "rewrite");
            rewritten = rewrite(query);
        } finally {
            stamps[0] = System.currentTimeMillis() - t0;
            stageTimers.get("rewrite").record(stamps[0], TimeUnit.MILLISECONDS);
            MDC.remove(PipelineMdc.KEY_STAGE);
        }

        // Step 2: cache check (uses rewritten text + tenant — NOT raw text,
        // because two raw queries that rewrite to the same thing should share
        // a cached answer)
        String queryHash = hashQuery(rewritten.rewritten());
        long cacheCheckStart = System.currentTimeMillis();
        PipelineMdc.put(PipelineMdc.KEY_STAGE, "cacheCheck");
        Optional<Answer> hit;
        try {
            hit = checkCache(query, queryHash);
        } finally {
            stamps[1] = System.currentTimeMillis() - cacheCheckStart;
            stageTimers.get("cacheCheck").record(stamps[1], TimeUnit.MILLISECONDS);
            MDC.remove(PipelineMdc.KEY_STAGE);
        }
        if (hit.isPresent()) {
            Answer a = hit.get();
            long latency = System.currentTimeMillis() - t0;
            // The cached Answer carries the source of the original request
            // (probably LLM). On a cache HIT we override to CACHE — that's the
            // leg that produced THIS request's response. Latency is freshly
            // measured (the cached one reflects the original request).
            Counter.builder("rag.qa.requests.total")
                    .tag("tenant", query.tenantId())
                    .tag("source", AnswerSource.CACHE.name())
                    .register(meterRegistry)
                    .increment();
            return new Answer(
                    a.tenantId(),
                    a.queryHash(),
                    a.rewrittenQuery(),
                    a.retrieved(),
                    a.reranked(),
                    a.finalText(),
                    a.citations(),
                    AnswerSource.CACHE,
                    latency,
                    a.metrics());
        }

        // Step 3: embed (cached) — may throw EmbeddingUnavailableException (propagates)
        long embedStart = System.currentTimeMillis();
        PipelineMdc.put(PipelineMdc.KEY_STAGE, "embed");
        float[] vec;
        try {
            vec = embed(query, rewritten.rewritten());
        } finally {
            stamps[2] = System.currentTimeMillis() - embedStart;
            stageTimers.get("embed").record(stamps[2], TimeUnit.MILLISECONDS);
            MDC.remove(PipelineMdc.KEY_STAGE);
        }

        // Step 4: retrieve — may throw VectorStoreUnavailableException (propagates)
        long retrieveStart = System.currentTimeMillis();
        PipelineMdc.put(PipelineMdc.KEY_STAGE, "retrieve");
        int topK = query.topK() > 0 ? query.topK() : DEFAULT_TOP_K;
        List<Chunk> retrieved;
        try {
            retrieved = retrieve(query, vec, topK);
        } finally {
            stamps[3] = System.currentTimeMillis() - retrieveStart;
            stageTimers.get("retrieve").record(stamps[3], TimeUnit.MILLISECONDS);
            MDC.remove(PipelineMdc.KEY_STAGE);
        }

        // Spec §9.1 — rag.qa.retrieved.chunks.count
        Counter.builder("rag.qa.retrieved.chunks.count")
                .tag("tenant", query.tenantId())
                .register(meterRegistry)
                .increment(retrieved.size());

        // Step 5: rerank (with fallback on failure)
        long rerankStart = System.currentTimeMillis();
        PipelineMdc.put(PipelineMdc.KEY_STAGE, "rerank");
        int topN = DEFAULT_TOP_N;
        List<Chunk> reranked;
        try {
            reranked = rerank(rewritten.rewritten(), retrieved, topN);
            // Spec §9.1 — rag.qa.rerank.delta.score{tenant} (best-effort)
            // Call rerankWithScores() to get real relevance scores from the concrete impl.
            // The test stub overrides this to avoid double-capturing MDC.
            try {
                List<RerankResult> scored = reranker.rerankWithScores(rewritten.rewritten(), retrieved, topN);
                if (scored.size() >= 2) {
                    double maxScore = scored.stream().mapToDouble(RerankResult::relevanceScore).max().orElse(0);
                    double minScore = scored.stream().mapToDouble(RerankResult::relevanceScore).min().orElse(0);
                    meterRegistry.gauge("rag.qa.rerank.delta.score",
                            Tags.of("tenant", query.tenantId()),
                            maxScore - minScore);
                }
            } catch (RuntimeException e) {
                log.debug("Failed to compute rag.qa.rerank.delta.score: {}", e.getMessage());
            }
        } catch (RerankUnavailableException reu) {
            log.warn("QA rerank unavailable for tenant={} queryHash={} err={} — using TopK directly",
                    query.tenantId(), queryHash, reu.getMessage());
            Counter.builder("rag.qa.degradation.total")
                    .tag("tenant", query.tenantId())
                    .tag("reason", "rerank_unavailable")
                    .register(meterRegistry)
                    .increment();
            reranked = retrieved.size() > topN ? retrieved.subList(0, topN) : retrieved;
        } finally {
            stamps[4] = System.currentTimeMillis() - rerankStart;
            stageTimers.get("rerank").record(stamps[4], TimeUnit.MILLISECONDS);
            MDC.remove(PipelineMdc.KEY_STAGE);
        }

        // Empty retrieval → graceful "I don't know" with hot questions.
        if (reranked.isEmpty()) {
            // Count this terminal state as a FALLBACK_RULE request — the
            // caller did make a real request, and we did return a
            // non-empty (graceful) answer, so it should show up in the
            // requests.total counter (spec §9.1).
            Counter.builder("rag.qa.requests.total")
                    .tag("tenant", query.tenantId())
                    .tag("source", AnswerSource.FALLBACK_RULE.name())
                    .register(meterRegistry)
                    .increment();
            return emptyRetrievalAnswer(query, rewritten, queryHash, t0);
        }

        // Step 6: assemble prompt (token budget)
        long assembleStart = System.currentTimeMillis();
        PipelineMdc.put(PipelineMdc.KEY_STAGE, "assemble");
        AssembledPrompt assembled;
        try {
            assembled = contextAssembler.assemble(
                    reranked, rewritten.rewritten(), ContextAssembler.DEFAULT_TOKEN_BUDGET);
        } finally {
            stamps[5] = System.currentTimeMillis() - assembleStart;
            stageTimers.get("assemble").record(stamps[5], TimeUnit.MILLISECONDS);
            MDC.remove(PipelineMdc.KEY_STAGE);
        }

        // Spec §9.1 — rag.qa.context.tokens
        meterRegistry.gauge(
                "rag.qa.context.tokens",
                Tags.of("tenant", query.tenantId()),
                assembled,
                AssembledPrompt::promptTokens);

        // Step 7: generate (with fallback on LLM failure)
        long generateStart = System.currentTimeMillis();
        PipelineMdc.put(PipelineMdc.KEY_STAGE, "generate");
        String finalText;
        AnswerSource source;
        try {
            finalText = llm.generateAnswer(query.tenantId(), assembled.fullPrompt());
            source = AnswerSource.LLM;
        } catch (LlmUnavailableException lue) {
            log.warn("QA LLM unavailable for tenant={} queryHash={} err={} — falling back to FALLBACK_RULE",
                    query.tenantId(), queryHash, lue.getMessage());
            Counter.builder("rag.qa.degradation.total")
                    .tag("tenant", query.tenantId())
                    .tag("reason", "llm_unavailable")
                    .register(meterRegistry)
                    .increment();
            finalText = fallbackFromChunks(rewritten.rewritten(), reranked);
            source = AnswerSource.FALLBACK_RULE;
        } catch (RuntimeException re) {
            // Any unexpected runtime exception from the LLM is treated as
            // upstream failure — never let it bubble past QA and crash the
            // request thread.
            log.warn("QA LLM threw unexpected for tenant={} queryHash={} err={} — falling back to FALLBACK_RULE",
                    query.tenantId(), queryHash, re.getMessage());
            Counter.builder("rag.qa.degradation.total")
                    .tag("tenant", query.tenantId())
                    .tag("reason", "llm_unavailable")
                    .register(meterRegistry)
                    .increment();
            finalText = fallbackFromChunks(rewritten.rewritten(), reranked);
            source = AnswerSource.FALLBACK_RULE;
        } finally {
            stamps[6] = System.currentTimeMillis() - generateStart;
            stageTimers.get("generate").record(stamps[6], TimeUnit.MILLISECONDS);
            MDC.remove(PipelineMdc.KEY_STAGE);
        }

        // Publish the requests counter with the final source tag.
        Counter.builder("rag.qa.requests.total")
                .tag("tenant", query.tenantId())
                .tag("source", source.name())
                .register(meterRegistry)
                .increment();

        Answer answer = new Answer(
                query.tenantId(),
                queryHash,
                rewritten.rewritten(),
                retrieved,
                reranked,
                finalText,
                assembled.citations(),
                source,
                System.currentTimeMillis() - t0,
                Map.of(
                        "stage.rewrite.ms", stamps[0],
                        "stage.cacheCheck.ms", stamps[1],
                        "stage.embed.ms", stamps[2],
                        "stage.retrieve.ms", stamps[3],
                        "stage.rerank.ms", stamps[4],
                        "stage.assemble.ms", stamps[5],
                        "stage.generate.ms", stamps[6],
                        "anyTruncated", assembled.anyTruncated()));

        // Step 8: cache write (best-effort)
        long cachePutStart = System.currentTimeMillis();
        PipelineMdc.put(PipelineMdc.KEY_STAGE, "cachePut");
        try {
            cachePut(query.tenantId(), queryHash, answer);
        } finally {
            stamps[7] = System.currentTimeMillis() - cachePutStart;
            MDC.remove(PipelineMdc.KEY_STAGE);
        }

        return answer;
    }

    // ─── step implementations ─────────────────────────────────────────────

    /** Step 1: rewrite. Always succeeds (RuleBased never throws). */
    private RewriteResult rewrite(Query query) {
        return rewriter.rewrite(query.tenantId(), query.rawText());
    }

    /** Step 2: answer cache check. Returns empty on miss / error. */
    private Optional<Answer> checkCache(Query query, String queryHash) {
        try {
            Optional<Answer> hit = answerCache.get(query.tenantId(), queryHash);
            if (hit.isPresent()) {
                if (log.isDebugEnabled()) {
                    log.debug("QA cache HIT tenant={} queryHash={}", query.tenantId(), queryHash);
                }
            }
            return hit;
        } catch (Exception e) {
            log.warn("QA cache.get failure tenant={} queryHash={} err={} — treating as miss",
                    query.tenantId(), queryHash, e.getMessage());
            return Optional.empty();
        }
    }

    /** Step 3: embed via cache or gateway. May throw EmbeddingUnavailableException. */
    private float[] embed(Query query, String rewrittenText) {
        // EmbeddingCache is keyed on textHash WITHOUT tenantId (cross-tenant
        // embeddings are identical — the cache is content-keyed only).
        // The tenantId is recorded here purely for log / metric labels.
        String textHash = hashQuery(rewrittenText);
        float[] cached = safeEmbeddingCacheLookup(textHash);
        if (cached != null) {
            return cached;
        }
        // Gateway does NOT have an embedSingle — we always call embedBatch.
        // For a single-query batch, that's fine; the impl can short-circuit.
        List<float[]> result = embeddingGateway.embedBatch(List.of(rewrittenText));
        if (result == null || result.isEmpty() || result.get(0) == null) {
            throw new EmbeddingUnavailableException(
                    "embedding gateway returned no vector for queryHash=" + textHash);
        }
        float[] vec = result.get(0);
        safeEmbeddingCacheStore(textHash, vec);
        return vec;
    }

    private float[] safeEmbeddingCacheLookup(String textHash) {
        try {
            return embeddingCache.get(textHash);
        } catch (Exception e) {
            log.warn("EmbeddingCache.get failure err={} — bypassing", e.getMessage());
            return null;
        }
    }

    private void safeEmbeddingCacheStore(String textHash, float[] vec) {
        try {
            embeddingCache.put(textHash, vec);
        } catch (Exception e) {
            log.warn("EmbeddingCache.put failure err={} — skipping", e.getMessage());
        }
    }

    /** Step 4: vector search with the standard visibility filter. */
    private List<Chunk> retrieve(Query query, float[] vec, int topK) {
        // Query carries kbVersion (a KbVersion record) — kbId + version live
        // there. A null kbVersion means "use the currently-published one";
        // the VectorStore port resolves that internally.
        String kbId = query.kbVersion() == null ? null : query.kbVersion().kbId();
        long version = query.kbVersion() == null ? -1L : query.kbVersion().version();
        return vectorStore.search(
                vec,
                query.tenantId(),
                kbId,
                version,
                new ArrayList<>(query.permissionTags()),
                io.github.yysf1949.rag.core.model.PermissionMode.AND,
                topK);
    }

    /** Step 5: rerank (TopK → TopN). Throws RerankUnavailableException — caller catches. */
    private List<Chunk> rerank(String queryText, List<Chunk> candidates, int topN) {
        return reranker.rerank(queryText, candidates, topN);
    }

    /** Step 7 fallback: concatenate retrieved chunks verbatim with citation markers. */
    static String fallbackFromChunks(String queryText, List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("根据检索到的资料：\n");
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            sb.append("[").append(i + 1).append("] ");
            sb.append(safe(c.title())).append(" › ").append(safe(c.sectionPath())).append('\n');
            sb.append(c.content()).append('\n');
        }
        if (queryText != null && !queryText.isBlank()) {
            sb.append("\n（针对问题：").append(queryText).append("）");
        }
        return sb.toString();
    }

    /** Empty-retrieval graceful answer with hot questions. */
    private Answer emptyRetrievalAnswer(Query query, RewriteResult rewritten, String queryHash, long t0) {
        List<String> hot = hotQuestions.recent(query.tenantId(), 5);
        StringBuilder sb = new StringBuilder();
        sb.append("抱歉，知识库中没有找到与您问题相关的内容。");
        if (!hot.isEmpty()) {
            sb.append("\n\n您可以试试问：\n");
            for (String q : hot) {
                sb.append("• ").append(q).append('\n');
            }
        }
        return new Answer(
                query.tenantId(),
                queryHash,
                rewritten.rewritten(),
                List.of(),
                List.of(),
                sb.toString(),
                List.of(),
                AnswerSource.FALLBACK_RULE,
                System.currentTimeMillis() - t0,
                Map.of("stage.retrieval.empty", true));
    }

    /** Step 8: best-effort cache write. */
    private void cachePut(String tenantId, String queryHash, Answer answer) {
        try {
            answerCache.put(tenantId, queryHash, answer);
        } catch (Exception e) {
            log.warn("QA cache.put failure tenant={} queryHash={} err={} — continuing",
                    tenantId, queryHash, e.getMessage());
        }
    }

    /**
     * Build a {@code Map<stageName, Timer>} for the per-stage latency
     * metrics. Called once per request to avoid re-allocating Timer
     * references inside the hot loop. Timers are tagged with the tenant
     * so a single registry can serve multiple tenants.
     */
    private Map<String, Timer> buildStageTimers(String tenantId) {
        Map<String, Timer> out = new java.util.LinkedHashMap<>(STAGE_NAMES.size());
        for (String stage : STAGE_NAMES) {
            out.put(stage, Timer.builder("rag.qa.latency.ms")
                    .tag("stage", stage)
                    .tag("tenant", tenantId == null ? "unknown" : tenantId)
                    .register(meterRegistry));
        }
        return out;
    }

    /**
     * SHA-256 hex of the <b>rewritten</b> text — two raw queries that
     * collapse to the same rewrite must share a cached answer.
     */
    static String hashQuery(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase().replaceAll("\\s+", " ");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Build the per-stage timings map for the Answer's metrics payload.
     * Exposed for tests that want to verify stage splits.
     */
    static Map<String, Object> stageMetrics(long[] stamps, boolean anyTruncated) {
        return Map.of(
                "stage.rewrite.ms", stamps[0],
                "stage.cacheCheck.ms", stamps[1],
                "stage.embed.ms", stamps[2],
                "stage.retrieve.ms", stamps[3],
                "stage.rerank.ms", stamps[4],
                "stage.assemble.ms", stamps[5],
                "stage.generate.ms", stamps[6],
                "stage.cachePut.ms", stamps[7],
                "anyTruncated", anyTruncated);
    }

    /**
     * Public surface for the legacy {@code Query#permissionTags} helper — used
     * by tests to construct a SearchRequest without dealing with the Set
     * overload.
     */
    public static Set<String> tagSet(String... tags) {
        Set<String> out = new java.util.HashSet<>();
        for (String t : tags) {
            if (t != null && !t.isBlank()) out.add(t);
        }
        return out;
    }

    /** Defensive: callers may pass an empty/negative topN — we floor it. */
    static int safeTopN(int requested) {
        return requested > 0 ? requested : DEFAULT_TOP_N;
    }

    /**
     * Build a SearchRequest from a Query and a query-vector. Convenience for
     * tests / decorators that want to bypass the embed step.
     */
    public static List<Chunk> callSearch(VectorStore store, Query q, float[] vec, int topK) {
        String kbId = q.kbVersion() == null ? null : q.kbVersion().kbId();
        long version = q.kbVersion() == null ? -1L : q.kbVersion().version();
        return store.search(
                vec,
                q.tenantId(),
                kbId,
                version,
                new ArrayList<>(q.permissionTags()),
                io.github.yysf1949.rag.core.model.PermissionMode.AND,
                topK);
    }

    /** Small helper: copy-and-truncate a list (used in fallback path). */
    static <T> List<T> take(List<T> in, int n) {
        if (in == null || in.isEmpty()) return List.of();
        return new ArrayList<>(in.subList(0, Math.min(n, in.size())));
    }
}
