package io.github.yysf1949.rag.core.port;

/**
 * Query rewriter — turns noisy user input into a clean embedding-friendly query.
 *
 * <p>Design spec §11.2 — implementations chain a fast rule-based pass
 * (synonyms, stop-words, polite-prefix stripping) with an optional LLM
 * fallback when rule confidence is below a threshold (default 0.6).
 * Result is cached at {@code rag:rewrite-cache:{tenant}:{queryHash}}.</p>
 */
public interface RewriteService {

    /**
     * Rewrite {@code rawText} for embedding + retrieval.
     *
     * @param tenantId   scope the rewrite to the tenant's synonym table
     * @param rawText    user input
     * @return rewrite result carrying the new text + a confidence score
     */
    RewriteResult rewrite(String tenantId, String rawText);

    /**
     * Output of a rewrite pass.
     *
     * @param rewritten   final text to embed
     * @param ruleScore   0..1 — confidence of the rule-based leg
     * @param usedLlm     whether the LLM fallback was triggered
     */
    record RewriteResult(String rewritten, double ruleScore, boolean usedLlm) {
        public RewriteResult {
            if (rewritten == null || rewritten.isBlank()) {
                throw new IllegalArgumentException("rewritten must not be blank");
            }
            if (ruleScore < 0 || ruleScore > 1) {
                throw new IllegalArgumentException("ruleScore must be in [0, 1], got " + ruleScore);
            }
        }

        /** Build a no-op rewrite (rawText passes through unchanged, max rule score). */
        public static RewriteResult identity(String rawText) {
            return new RewriteResult(rawText, 1.0, false);
        }
    }
}