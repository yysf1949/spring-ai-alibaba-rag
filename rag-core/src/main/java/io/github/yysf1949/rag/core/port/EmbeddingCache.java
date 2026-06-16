package io.github.yysf1949.rag.core.port;

import java.util.List;
import java.util.Map;

/**
 * Embedding vector cache — design spec §13.5 + §13.7.
 *
 * <p>Keyed on sha-256(text) so the cache survives embedding model upgrades
 * only when the new model produces identical hashes (which it does, since
 * the input text is the same). Dimension mismatches between cached vector
 * and the live model are detected by the {@code dimension} field on
 * {@link EmbeddingGateway}.</p>
 *
 * <p>Implementations must:</p>
 * <ul>
 *   <li>Return {@code null} (miss) on lookup failure, not throw.</li>
 *   <li>Cap entries via TTL + LRU (Redis Stack 7.4 handles LRU via
 *       {@code allkeys-lru} or per-key {@code EXPIRE}).</li>
 *   <li>Support bulk {@link #getMany(List)} — the embedding gateway batches
 *       to amortize round trips.</li>
 * </ul>
 */
public interface EmbeddingCache {

    /** @return vector for {@code sha256(text)}, or null on miss. */
    float[] get(String textHash);

    /** @return vectors in the same order; null entries are misses. */
    List<float[]> getMany(List<String> textHashes);

    /** Best-effort store. */
    void put(String textHash, float[] vector);

    /** Bulk best-effort store. */
    void putMany(Map<String, float[]> entries);

    /** @return hit ratio since process start. */
    default double hitRatio() {
        return 0.0;
    }
}
