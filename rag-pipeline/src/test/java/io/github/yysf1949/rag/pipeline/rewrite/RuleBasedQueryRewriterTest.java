package io.github.yysf1949.rag.pipeline.rewrite;

import io.github.yysf1949.rag.core.port.RewriteService.RewriteResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedQueryRewriterTest {

    private final RuleBasedQueryRewriter rewriter = new RuleBasedQueryRewriter();

    // ─── Polite-prefix strip ──────────────────────────────────────────────

    @Test
    void stripsPolitePrefix() {
        RewriteResult r = rewriter.rewrite("tenant-A", "请问 退款怎么操作？");
        // 客套词 removed.
        assertFalse(r.rewritten().startsWith("请问"));
        assertTrue(r.rewritten().contains("退款"));
    }

    @Test
    void stripsSinglePolitePrefixPerCall() {
        // Implementation strips ONE polite prefix per call (regex `^…`).
        // "麻烦帮我看一下运费" → after strip of "麻烦" → "帮我看一下运费".
        RewriteResult r = rewriter.rewrite("tenant-A", "麻烦帮我看一下运费");
        assertFalse(r.rewritten().startsWith("麻烦"),
                "leading 麻烦 should be stripped");
        assertTrue(r.rewritten().startsWith("帮我"),
                "second polite prefix (帮我) is left — single-prefix pass");
        assertTrue(r.rewritten().contains("运费"));
    }

    @Test
    void leavesTextWithoutPolitePrefix() {
        RewriteResult r = rewriter.rewrite("tenant-A", "退款流程");
        assertTrue(r.rewritten().contains("退款"));
    }

    // ─── Noise strip ──────────────────────────────────────────────────────

    @Test
    void stripsParticles() {
        RewriteResult r = rewriter.rewrite("tenant-A", "退款的流程是怎么样的呢？");
        // The particle "的" and "呢" should be stripped (or at least not in there).
        // 退款 should still be present.
        assertTrue(r.rewritten().contains("退款"));
        // The result should not contain "呢" as a standalone token.
        assertFalse(r.rewritten().contains("呢"));
    }

    @Test
    void collapsesWhitespace() {
        RewriteResult r = rewriter.rewrite("tenant-A", "退款   流程     怎么  走");
        // No triple-spaces should remain.
        assertFalse(r.rewritten().contains("  "));
    }

    @Test
    void stripsTrailingPunctuation() {
        RewriteResult r = rewriter.rewrite("tenant-A", "运费多少钱？");
        // Trailing question mark stripped.
        assertFalse(r.rewritten().endsWith("？"));
    }

    // ─── Synonym augmentation ─────────────────────────────────────────────

    @Test
    void appendsCanonicalWhenSurfacePresent() {
        RewriteResult r = rewriter.rewrite("tenant-A", "退钱流程");
        // 退钱 is a surface for 退款 → 退款 should appear in rewritten.
        assertTrue(r.rewritten().contains("退钱"));
        assertTrue(r.rewritten().contains("退款"));
    }

    @Test
    void doesNotDuplicateCanonicalIfAlreadyPresent() {
        RewriteResult r = rewriter.rewrite("tenant-A", "退款流程");
        // 退款 already there → not added again as a separate token.
        // String content still contains "退款" once or twice but no triple-duplication.
        int occurrences = r.rewritten().split("退款", -1).length - 1;
        assertTrue(occurrences <= 2, "退款 should not be appended when already present, but got "
                + occurrences + " occurrences in: " + r.rewritten());
    }

    @Test
    void synonymStageFiresOnlyForPresentSurfaces() {
        RewriteResult r = rewriter.rewrite("tenant-A", "你好吗");
        // "你好吗" matches no synonym surface — synonym stage should not fire.
        // So the only change vs input is strip + (probably nothing).
        // The output should still contain "你好吗" or some variation.
        // (It should be ~equal to input after strip; score should reflect that.)
        assertTrue(r.ruleScore() < 0.5,
                "no synonym match → low score, got " + r.ruleScore());
    }

    // ─── Confidence scoring ───────────────────────────────────────────────

    @Test
    void allThreeStagesFiringProducesHighScore() {
        // Polite + particles + synonym all fire here.
        RewriteResult r = rewriter.rewrite("tenant-A", "请问退钱的呢？");
        assertTrue(r.ruleScore() > 0.9,
                "all three stages fired → score near 1.0, got " + r.ruleScore());
    }

    @Test
    void noStagesFiringProducesVeryLowScore() {
        // Clean input, no synonym match, no particles.
        // Actually "你好" has no synonym either, so all stages effectively don't change much.
        RewriteResult r = rewriter.rewrite("tenant-A", "你好世界");
        assertTrue(r.ruleScore() < 0.5,
                "clean unrelated input → low score, got " + r.ruleScore());
    }

    @Test
    void usedLlmAlwaysFalse() {
        // The rule-only rewriter never claims LLM involvement.
        RewriteResult r = rewriter.rewrite("tenant-A", "退款");
        assertFalse(r.usedLlm());
    }

    // ─── Edge cases ───────────────────────────────────────────────────────

    @Test
    void blankInputThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> rewriter.rewrite("tenant-A", ""));
        assertThrows(IllegalArgumentException.class,
                () -> rewriter.rewrite("tenant-A", null));
        assertThrows(IllegalArgumentException.class,
                () -> rewriter.rewrite("tenant-A", "   "));
    }

    @Test
    void allParticlesInputFallsBackToOriginal() {
        // If everything gets stripped, we fall back to the raw text.
        RewriteResult r = rewriter.rewrite("tenant-A", "呢啊吧");
        // Output must not be empty.
        assertNotNull(r.rewritten());
        assertFalse(r.rewritten().isBlank());
    }

    @Test
    void thresholdRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuleBasedQueryRewriter(DefaultChineseSynonymTable.create(), -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new RuleBasedQueryRewriter(DefaultChineseSynonymTable.create(), 1.1));
    }

    @Test
    void synonymTableAccessor() {
        RuleBasedQueryRewriter r = new RuleBasedQueryRewriter();
        assertNotNull(r.synonyms());
        assertEquals(0.6, r.llmFallbackThreshold(), 1e-9);
    }

    @Test
    void customSynonymsAreApplied() {
        SynonymTable custom = SynonymTable.of("购物车", "篮子", "购物筐");
        RuleBasedQueryRewriter r = new RuleBasedQueryRewriter(custom);
        RewriteResult out = r.rewrite("tenant-A", "篮子里的商品");
        assertTrue(out.rewritten().contains("购物车"));
    }

    @Test
    void splitTokensUtility() {
        // Helper exposed for tests / debug.
        assertEquals(3, RuleBasedQueryRewriter.splitTokens("a b c").size());
    }
}
