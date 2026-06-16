package io.github.yysf1949.rag.core.port;

import io.github.yysf1949.rag.core.model.Chunk;

import java.util.List;

/**
 * Re-rank candidates retrieved from vector search.
 *
 * <p>Design spec §11.3, §13.8 — the default impl uses DashScope
 * {@code gte-rerank}, but the port is decoupled so the implementation can
 * be swapped (Cohere / BGE / local cross-encoder) via a single Spring
 * {@code @Primary} bean or a profile property.</p>
 *
 * <p>Implementations should never throw on transient upstream errors —
 * degrade to "return the input list truncated to topN" and let the caller
 * log the degradation (spec §7.5).</p>
 */
public interface RerankService {

    /**
     * Re-order {@code candidates} against {@code query}, returning the top-N
     * entries of the new ordering.
     *
     * @param query       rewritten query text
     * @param candidates  retrieval pool from {@link VectorStore#search}
     * @param topN        size to return (typically 5 — spec §8.1)
     * @return reranked chunks, length {@code min(topN, candidates.size())}
     */
    List<Chunk> rerank(String query, List<Chunk> candidates, int topN);
}