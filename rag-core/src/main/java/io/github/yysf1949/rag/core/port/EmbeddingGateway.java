package io.github.yysf1949.rag.core.port;

import java.util.List;
import java.util.Map;

/**
 * Embedding service contract — implemented in {@code rag-embedding} via
 * DashScope {@code text-embedding-v3} (1536 dim).
 *
 * <p>Design spec §13.5 — the contract mandates:
 * <ol>
 *   <li>Batch (callers should batch; the impl may further sub-batch).</li>
 *   <li>Deterministic caching keyed on sha-256(text) — bypassed when
 *       {@link #embedWithoutCache} is called.</li>
 *   <li>Retries on transient errors with exponential backoff.</li>
 *   <li>Degradation via {@link io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException}.</li>
 * </ol>
 */
public interface EmbeddingGateway {

    /**
     * Embed a batch of texts, transparently consulting the embedding cache
     * (sha-256 → vector) before going to the provider.
     *
     * @param texts non-empty list
     * @return vectors in the same order as input
     * @throws io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException
     *         if the upstream provider is unavailable after retries
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * Bypass cache — used for cache-warm-up and admin re-embedding.
     */
    List<float[]> embedWithoutCache(List<String> texts);

    /**
     * @return configured embedding dimension (DashScope v3 = 1536)
     */
    int dimension();

    /**
     * Bulk-write (text, vector) pairs into the cache. Typically called from
     * a back-fill job after a model version bump.
     */
    void warmCache(Map<String, float[]> entries);
}