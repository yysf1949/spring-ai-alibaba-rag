package io.github.yysf1949.rag.pipeline.context;

/**
 * Heuristic token counter — {@code chars / 2} rounded up.
 *
 * <p>Spec §13.10 doesn't pin a tokenizer. For mixed Chinese / English text
 * the ratio is ~1.3 chars/token; pure English is ~4 chars/token. {@code 2}
 * is a conservative middle that over-estimates slightly (safer — the LLM
 * never sees more than we promised). If you need exact counts for a
 * specific model, swap in {@code QwenTokenCounter} etc. via the
 * {@link ContextAssembler} constructor.</p>
 *
 * <p>This implementation is stateless and thread-safe.</p>
 */
public final class ApproxCharTokenCounter implements TokenCounter {

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // ceil(chars / 2): for length N we want ceil(N/2). The identity
        // ceil(N/2) = (N + 1) / 2 holds for integer division (N >= 0).
        // This slightly OVER-estimates for odd N, which is the safe
        // direction — the LLM will see at most what we promised.
        return (text.length() + 1) / 2;
    }
}
