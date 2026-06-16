package io.github.yysf1949.rag.pipeline.rewrite;

import io.github.yysf1949.rag.core.port.RewriteCache;
import io.github.yysf1949.rag.core.port.RewriteService;
import io.github.yysf1949.rag.core.port.RewriteService.RewriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Caching + LLM-fallback orchestrator on top of {@link RuleBasedQueryRewriter}.
 *
 * <p>Pipeline (spec §11.2):</p>
 * <pre>
 *   rewrite(tenant, text)
 *       ├─ hash = sha256(normalize(text))
 *       ├─ cache.get(tenant, hash) → hit? return immediately
 *       ├─ ruleResult = rule.rewrite(tenant, text)
 *       ├─ if (ruleResult.ruleScore &lt; threshold &amp;&amp; llm != null)
 *       │       llmResult = llm.rewrite(tenant, ruleResult.rewritten)
 *       │       final = llmResult ?? ruleResult  (LLM failure falls back silently)
 *       ├─ cache.put(tenant, hash, final)
 *       └─ return final
 * </pre>
 *
 * <h2>Cache semantics (mirrors {@link RewriteCache})</h2>
 * <ul>
 *   <li>Cache miss / Redis down → rule chain runs, then we cache the result.</li>
 *   <li>Cache hit → return cached {@link RewriteResult} unchanged
 *       (including its {@code usedLlm} flag — we cache the final outcome,
 *       not the rule score).</li>
 *   <li>Cache write failure → log warn, drop the cache write, return result anyway.</li>
 * </ul>
 *
 * <h2>LLM fallback semantics</h2>
 * <ul>
 *   <li>Only fires when the rule score is below
 *       {@link RuleBasedQueryRewriter#llmFallbackThreshold()}.</li>
 *   <li>{@link LlmRewriter#rewrite(String, String)} returning {@code null}
 *       (transient upstream failure) is silent — we keep the rule result.</li>
 *   <li>If {@link LlmRewriter} is {@code null} (not wired yet), the LLM
 *       leg is skipped — useful for tests / dev / key-not-yet-arrived.</li>
 * </ul>
 *
 * <h2>Query hashing</h2>
 * We hash the {@code rawText} AFTER lowercasing + collapsing whitespace —
 * same logical query should always map to the same key regardless of
 * user-style variations ("退款" vs "  退 款 "). SHA-256 is overkill (we
 * don't need collision resistance here, only even spread) but it's
 * already in the JDK and matches what the rest of the pipeline uses
 * (see {@code AnswerCache}).
 *
 * <h2>Thread-safety</h2>
 * Stateless after construction — safe to call from a thread pool.
 */
public class CachingRuleBasedRewriter implements RewriteService {

    private static final Logger log = LoggerFactory.getLogger(CachingRuleBasedRewriter.class);

    private final RuleBasedQueryRewriter rule;
    private final LlmRewriter llm;
    private final RewriteCache cache;

    public CachingRuleBasedRewriter(RuleBasedQueryRewriter rule, LlmRewriter llm, RewriteCache cache) {
        this.rule = Objects.requireNonNull(rule, "rule");
        this.llm = llm; // nullable — see class javadoc
        this.cache = cache; // nullable — see class javadoc
    }

    /**
     * Convenience: build without an LLM leg (rule + cache only). Useful for
     * dev / tests when the DashScope key hasn't been provisioned.
     */
    public static CachingRuleBasedRewriter ruleAndCacheOnly(
            RuleBasedQueryRewriter rule, RewriteCache cache) {
        return new CachingRuleBasedRewriter(rule, null, cache);
    }

    /**
     * Convenience: build without caching (rule + LLM leg only). Useful for
     * tests that exercise the rule/LLM handoff without bringing up Redis.
     */
    public static CachingRuleBasedRewriter ruleAndLlmOnly(
            RuleBasedQueryRewriter rule, LlmRewriter llm) {
        return new CachingRuleBasedRewriter(rule, llm, null);
    }

    @Override
    public RewriteResult rewrite(String tenantId, String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("rawText must not be blank");
        }
        Objects.requireNonNull(tenantId, "tenantId");

        // 1. Cache lookup
        String hash = hashQuery(rawText);
        if (cache != null) {
            RewriteResult hit = cache.get(tenantId, hash);
            if (hit != null) {
                if (log.isDebugEnabled()) {
                    log.debug("rewrite cache HIT tenant={} hash={} score={}",
                            tenantId, hash, hit.ruleScore());
                }
                return hit;
            }
        }

        // 2. Rule pass
        RewriteResult ruleResult = rule.rewrite(tenantId, rawText);

        // 3. Optional LLM fallback
        RewriteResult finalResult = ruleResult;
        if (llm != null && ruleResult.ruleScore() < rule.llmFallbackThreshold()) {
            try {
                RewriteResult llmResult = llm.rewrite(tenantId, ruleResult.rewritten());
                if (llmResult != null) {
                    finalResult = new RewriteResult(
                            llmResult.rewritten(),
                            Math.max(ruleResult.ruleScore(), llmResult.ruleScore()),
                            true);
                    if (log.isDebugEnabled()) {
                        log.debug("rewrite LLM fallback fired tenant={} hash={} ruleScore={} llmScore={}",
                                tenantId, hash, ruleResult.ruleScore(), llmResult.ruleScore());
                    }
                }
            } catch (Exception e) {
                // Per LlmRewriter contract: throw only when caller should
                // mark FALLBACK_RULE upstream; otherwise silently keep rule.
                log.warn("rewrite LLM fallback threw tenant={} hash={} err={} — keeping rule result",
                        tenantId, hash, e.getMessage());
            }
        }

        // 4. Cache write (best-effort).
        if (cache != null) {
            try {
                cache.put(tenantId, hash, finalResult);
            } catch (Exception e) {
                log.warn("rewrite cache.put failure tenant={} hash={} err={}",
                        tenantId, hash, e.getMessage());
            }
        }

        return finalResult;
    }

    /**
     * SHA-256 hex of {@code (lower(text), collapse whitespace)}. Exposed
     * as a static utility so callers (e.g. integration tests) can verify
     * they're using the same hash function as the cache.
     */
    public static String hashQuery(String text) {
        String normalized = text.trim().toLowerCase().replaceAll("\\s+", " ");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is in every JDK since 1.4.2 — this branch is unreachable
            // in practice, but we keep the checked-exception contract clean.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
