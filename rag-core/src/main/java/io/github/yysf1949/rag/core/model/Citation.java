package io.github.yysf1949.rag.core.model;

/**
 * Pointer to a chunk inside an {@link Answer}, surfaced to the UI so the user
 * can verify the answer's provenance.
 *
 * <p>Design spec §13.10 — {@code ContextAssembler} guarantees that
 * {@code title + sectionPath + sourceUri} survive the token-budget compression
 * even when {@code content} is truncated.</p>
 *
 * @param chunkId      the cited chunk
 * @param title        document title
 * @param sectionPath  breadcrumb like {@code 退款规则 / 运费条款}
 * @param sourceUri    original document URL or path
 * @param score        rerank score (1.0 = perfect match)
 */
public record Citation(
        String chunkId,
        String title,
        String sectionPath,
        String sourceUri,
        double score
) {}