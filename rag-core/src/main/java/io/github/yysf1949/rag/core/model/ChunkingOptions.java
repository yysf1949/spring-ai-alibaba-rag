package io.github.yysf1949.rag.core.model;

/**
 * Tunable knobs for {@code rag-pipeline}'s {@code ChunkSplitter}.
 *
 * <p>Design spec §6.2 — defaults aim for 200-800 token chunks with 50 token
 * overlap. Without a real tokenizer on the classpath we approximate in
 * characters: Chinese 1 token ≈ 1.5 chars, English 1 token ≈ 4 chars, so
 * a 600-token target is roughly 1200 chars mixed content. Override
 * {@link #maxChars} / {@link #overlapChars} for language-specific corpora.</p>
 *
 * @param maxChars        hard upper bound on a single chunk's {@code content} length
 *                        (default 1200 ≈ 600 mixed-content tokens)
 * @param overlapChars    characters of trailing context carried into the next chunk
 *                        (default 100 ≈ 50 tokens)
 * @param minChars        chunks shorter than this are merged with the next one
 *                        (default 80; spec implies a minimum semantic unit)
 */
public record ChunkingOptions(
        int maxChars,
        int overlapChars,
        int minChars
) {

    public static final int DEFAULT_MAX_CHARS = 1200;
    public static final int DEFAULT_OVERLAP_CHARS = 100;
    public static final int DEFAULT_MIN_CHARS = 80;

    public ChunkingOptions {
        if (maxChars < 64) {
            throw new IllegalArgumentException("maxChars must be >= 64, got " + maxChars);
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalArgumentException(
                    "overlapChars must be in [0, maxChars), got overlap=" + overlapChars
                            + " max=" + maxChars);
        }
        if (minChars < 0 || minChars > maxChars) {
            throw new IllegalArgumentException(
                    "minChars must be in [0, maxChars], got min=" + minChars
                            + " max=" + maxChars);
        }
    }

    public static ChunkingOptions defaults() {
        return new ChunkingOptions(DEFAULT_MAX_CHARS, DEFAULT_OVERLAP_CHARS, DEFAULT_MIN_CHARS);
    }
}
