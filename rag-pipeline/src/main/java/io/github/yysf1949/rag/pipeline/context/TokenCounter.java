package io.github.yysf1949.rag.pipeline.context;

/**
 * Counts tokens for {@link ContextAssembler}.
 *
 * <p>Spec §13.10 says "token 预算：默认 4000". It does NOT specify the
 * tokenizer — that choice is deployment-dependent (Qwen tokenizer vs
 * tiktoken vs heuristic). The interface is the abstraction so we can swap
 * in an exact tokenizer later without touching {@link ContextAssembler}.</p>
 *
 * <p>Implementations MUST be thread-safe and pure (no caching of mutable
 * state across calls).</p>
 */
@FunctionalInterface
public interface TokenCounter {

    /**
     * @return estimated token count for {@code text}. Must return
     *         {@code >= 0} (zero is valid for empty input).
     */
    int count(String text);
}
