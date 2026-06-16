package io.github.yysf1949.rag.pipeline.rewrite;

import io.github.yysf1949.rag.core.port.RewriteCache;
import io.github.yysf1949.rag.core.port.RewriteService.RewriteResult;
import org.junit.jupiter.api.Test;

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

class CachingRuleBasedRewriterTest {

    private final RuleBasedQueryRewriter rule = new RuleBasedQueryRewriter();

    // ─── in-memory cache test double ──────────────────────────────────────

    /** Tiny in-memory cache for tests — replaces Redis entirely. */
    static class InMemoryCache implements RewriteCache {
        final ConcurrentHashMap<String, RewriteResult> store = new ConcurrentHashMap<>();
        final AtomicInteger hits = new AtomicInteger();
        final AtomicInteger misses = new AtomicInteger();

        @Override
        public RewriteResult get(String tenantId, String queryHash) {
            RewriteResult r = store.get(key(tenantId, queryHash));
            if (r != null) hits.incrementAndGet(); else misses.incrementAndGet();
            return r;
        }

        @Override
        public boolean put(String tenantId, String queryHash, RewriteResult result) {
            store.put(key(tenantId, queryHash), result);
            return true;
        }

        @Override
        public double hitRatio(String tenantId) {
            int h = hits.get(), m = misses.get();
            return (h + m) == 0 ? 0.0 : (double) h / (h + m);
        }

        private static String key(String tenantId, String queryHash) {
            return tenantId + "::" + queryHash;
        }
    }

    /** Stub LLM rewriter — configurable to return null (failure) or a value. */
    static class StubLlm implements LlmRewriter {
        AtomicInteger calls = new AtomicInteger();
        RewriteResult toReturn = null;
        RuntimeException toThrow = null;

        @Override
        public RewriteResult rewrite(String tenantId, String rawText) {
            calls.incrementAndGet();
            if (toThrow != null) throw toThrow;
            return toReturn;
        }
    }

    // ─── cache hit / miss ─────────────────────────────────────────────────

    @Test
    void secondCallHitsCacheAndSkipsRuleAndLlm() {
        InMemoryCache cache = new InMemoryCache();
        StubLlm llm = new StubLlm();
        llm.toReturn = new RewriteResult("LLM-rewritten", 0.95, true);

        CachingRuleBasedRewriter cr =
                new CachingRuleBasedRewriter(rule, llm, cache);

        // First call: cache miss → rule + LLM.
        RewriteResult r1 = cr.rewrite("tenant-A", "退款");
        assertEquals(1, llm.calls.get(), "first call: LLM fires because rule score < 0.6");
        // After caching, the CACHED result is what we returned.
        // The cached final result was the LLM-upgraded one.
        RewriteResult r2 = cr.rewrite("tenant-A", "退款");
        assertEquals(1, llm.calls.get(),
                "second identical call: cache hit, NO extra LLM call");
        assertEquals(r1.rewritten(), r2.rewritten(), "cache hit returns same text");
    }

    @Test
    void cacheKeyIsTenantScoped() {
        InMemoryCache cache = new InMemoryCache();
        CachingRuleBasedRewriter cr = new CachingRuleBasedRewriter(rule, null, cache);

        cr.rewrite("tenant-A", "退款");
        // Different tenant — cache miss.
        cr.rewrite("tenant-B", "退款");

        // Two distinct cache entries, both stored.
        long stored = cache.store.values().stream().count();
        assertEquals(2, stored);
    }

    @Test
    void cacheKeyIsTextNormalized() {
        // Whitespace and case differences should map to the same key.
        InMemoryCache cache = new InMemoryCache();
        StubLlm llm = new StubLlm();
        llm.toReturn = new RewriteResult("LLM-rewritten", 0.95, true);
        CachingRuleBasedRewriter cr =
                new CachingRuleBasedRewriter(rule, llm, cache);

        cr.rewrite("tenant-A", "退款");
        // Case + whitespace variants.
        cr.rewrite("tenant-A", "  退款  ");
        cr.rewrite("tenant-A", "退款");

        // Only 1 cache entry.
        assertEquals(1, cache.store.size(),
                "normalized text variants should share a cache entry");
        // LLM only called on the FIRST call (subsequent are cache hits).
        assertEquals(1, llm.calls.get());
    }

    @Test
    void cacheWriteFailureDoesNotBreakRewrite() {
        RewriteCache broken = new RewriteCache() {
            @Override public RewriteResult get(String tenantId, String queryHash) { return null; }
            @Override public boolean put(String tenantId, String queryHash, RewriteResult result) {
                throw new RuntimeException("redis down");
            }
        };
        CachingRuleBasedRewriter cr =
                new CachingRuleBasedRewriter(rule, null, broken);

        // Should not throw — broken.put is wrapped in try/catch.
        RewriteResult r = cr.rewrite("tenant-A", "退款");
        assertNotNull(r);
    }

    // ─── LLM fallback handoff ─────────────────────────────────────────────

    @Test
    void llmFiresWhenRuleScoreBelowThreshold() {
        StubLlm llm = new StubLlm();
        llm.toReturn = new RewriteResult("LLM-out", 0.95, true);
        CachingRuleBasedRewriter cr =
                new CachingRuleBasedRewriter(rule, llm, new InMemoryCache());

        RewriteResult r = cr.rewrite("tenant-A", "你好世界"); // no synonym match → low score
        assertTrue(r.usedLlm(), "rule score < 0.6 → LLM should fire");
        assertEquals("LLM-out", r.rewritten());
    }

    @Test
    void llmNotCalledWhenRuleScoreHighEnough() {
        // Polite + particles + synonym all fire → high score.
        StubLlm llm = new StubLlm();
        llm.toReturn = new RewriteResult("LLM-out", 0.95, true);
        CachingRuleBasedRewriter cr =
                new CachingRuleBasedRewriter(rule, llm, new InMemoryCache());

        RewriteResult r = cr.rewrite("tenant-A", "请问退钱的呢？");
        assertFalse(r.usedLlm(), "rule score high → LLM skipped");
        assertEquals(0, llm.calls.get());
    }

    @Test
    void llmReturningNullFallsBackSilentlyToRule() {
        StubLlm llm = new StubLlm();
        llm.toReturn = null; // simulate transient upstream failure
        CachingRuleBasedRewriter cr =
                new CachingRuleBasedRewriter(rule, llm, new InMemoryCache());

        RewriteResult r = cr.rewrite("tenant-A", "你好世界");
        assertFalse(r.usedLlm(), "LLM returned null → usedLlm=false");
        assertNotNull(r.rewritten());
    }

    @Test
    void llmThrowingExceptionFallsBackSilentlyToRule() {
        StubLlm llm = new StubLlm();
        llm.toThrow = new RuntimeException("upstream 503");
        CachingRuleBasedRewriter cr =
                new CachingRuleBasedRewriter(rule, llm, new InMemoryCache());

        // Should not throw — exception is caught and logged.
        RewriteResult r = cr.rewrite("tenant-A", "你好世界");
        assertFalse(r.usedLlm());
    }

    @Test
    void llmNullSkipsLlmLeg() {
        // LLM not wired (e.g. dev without DashScope key) — should still work.
        CachingRuleBasedRewriter cr =
                new CachingRuleBasedRewriter(rule, null, new InMemoryCache());
        RewriteResult r = cr.rewrite("tenant-A", "你好世界");
        assertFalse(r.usedLlm());
    }

    @Test
    void llmResultScoreIsMaxOfRuleAndLlm() {
        StubLlm llm = new StubLlm();
        llm.toReturn = new RewriteResult("LLM-better", 0.99, true);
        CachingRuleBasedRewriter cr =
                new CachingRuleBasedRewriter(rule, llm, new InMemoryCache());

        RewriteResult r = cr.rewrite("tenant-A", "你好世界");
        assertTrue(r.ruleScore() >= 0.99,
                "final score should reflect LLM upgrade, got " + r.ruleScore());
        assertTrue(r.usedLlm());
    }

    // ─── Static helpers ───────────────────────────────────────────────────

    @Test
    void hashQueryIsDeterministicAndCaseInsensitive() {
        String h1 = CachingRuleBasedRewriter.hashQuery("退款");
        String h2 = CachingRuleBasedRewriter.hashQuery("退款");
        String h3 = CachingRuleBasedRewriter.hashQuery("退款 ");  // trailing space
        String h4 = CachingRuleBasedRewriter.hashQuery("退 款");   // inner space

        assertEquals(h1, h2);
        assertEquals(h1, h3, "trailing space should be ignored");
        assertNotEquals(h1, h4, "different word boundaries → different hash");
    }

    @Test
    void hashQueryIsSha256Hex() {
        String h = CachingRuleBasedRewriter.hashQuery("anything");
        assertEquals(64, h.length(), "SHA-256 hex is 64 chars");
        assertTrue(h.matches("[0-9a-f]{64}"));
    }

    // ─── Convenience factories ────────────────────────────────────────────

    @Test
    void ruleAndCacheOnlyFactorySkipsLlm() {
        CachingRuleBasedRewriter cr =
                CachingRuleBasedRewriter.ruleAndCacheOnly(rule, new InMemoryCache());
        RewriteResult r = cr.rewrite("tenant-A", "你好世界");
        assertFalse(r.usedLlm());
    }

    @Test
    void ruleAndLlmOnlyFactorySkipsCache() {
        StubLlm llm = new StubLlm();
        llm.toReturn = new RewriteResult("LLM-only", 0.95, true);
        CachingRuleBasedRewriter cr =
                CachingRuleBasedRewriter.ruleAndLlmOnly(rule, llm);

        RewriteResult r = cr.rewrite("tenant-A", "你好世界");
        assertTrue(r.usedLlm());
        // No cache → every call hits LLM.
        cr.rewrite("tenant-A", "你好世界");
        assertEquals(2, llm.calls.get());
    }

    // ─── Input validation ─────────────────────────────────────────────────

    @Test
    void blankInputThrows() {
        CachingRuleBasedRewriter cr =
                new CachingRuleBasedRewriter(rule, null, new InMemoryCache());
        assertThrows(IllegalArgumentException.class,
                () -> cr.rewrite("tenant-A", ""));
        assertThrows(IllegalArgumentException.class,
                () -> cr.rewrite("tenant-A", null));
    }

    @Test
    void nullTenantThrows() {
        CachingRuleBasedRewriter cr =
                new CachingRuleBasedRewriter(rule, null, new InMemoryCache());
        assertThrows(NullPointerException.class,
                () -> cr.rewrite(null, "退款"));
    }
}
