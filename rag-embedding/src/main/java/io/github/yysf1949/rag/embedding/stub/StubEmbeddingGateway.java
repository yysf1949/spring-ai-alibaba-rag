package io.github.yysf1949.rag.embedding.stub;

import io.github.yysf1949.rag.core.port.EmbeddingGateway;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Deterministic embedding stub — every text gets a 16-dim vector with a
 * per-text fingerprint (so cosine distance is well-defined, unlike pure
 * zero vectors). Caches results in-process for the lifetime of the JVM.
 *
 * <p>Real impl: {@code io.github.yysf1949.rag.embedding.dashscope.DashScopeEmbeddingGateway}
 * (Phase 5-P4, spec §13.5). When that ships, register it as
 * {@code @Primary} and the stub will be skipped by Spring's
 * {@code @ConditionalOnMissingBean} mechanism.</p>
 *
 * <p><b>Thread-safe:</b> the cache is a {@link ConcurrentHashMap}.</p>
 */
public class StubEmbeddingGateway implements EmbeddingGateway {

    /**
     * Default dim matches the RediSearch schema declared in
     * {@code io.github.yysf1949.rag.redis.vector.RedisIndexManager#DEFAULT_DIM} (1024).
     * The two MUST stay in sync — a mismatch silently drops every chunk during
     * index ingest (RediSearch refuses to index a VECTOR field whose byte length
     * does not match {@code DIM * sizeof(FLOAT32)}).
     */
    public static final int DIM = 1024;

    private final ConcurrentMap<String, float[]> cache = new ConcurrentHashMap<>();

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return texts.stream()
                .map(t -> cache.computeIfAbsent(t, k -> newZeroVec(DIM, k)))
                .toList();
    }

    @Override
    public List<float[]> embedWithoutCache(List<String> texts) {
        return texts.stream().map(t -> newZeroVec(DIM, t)).toList();
    }

    @Override
    public int dimension() {
        return DIM;
    }

    @Override
    public void warmCache(Map<String, float[]> entries) {
        cache.putAll(entries);
    }

    private static float[] newZeroVec(int dim, String text) {
        float[] v = new float[dim];
        // Character 3-gram bag-of-words hash: each trigram contributes
        // to 3 dimensions via modulo, producing a stable embedding where
        // similar texts (add/remove 1 char) produce similar vectors.
        // L2-normalize so cosine distance is well-defined.
        //
        // Identical text → identical vector (cosine = 1) ✓
        // Similar texts  → ~similar vectors (most trigrams overlap) ✓
        // Different text → different vectors ✓
        if (text == null || text.isEmpty()) {
            return v;
        }
        int n = text.length();
        int contributions = 0;
        for (int i = 0; i < n - 2; i++) {
            int trigram = (text.charAt(i) * 31 + text.charAt(i + 1)) * 31 + text.charAt(i + 2);
            for (int j = 0; j < 3; j++) {
                int dimIdx = Math.abs((trigram * 0x9E3779B9 + j * 7919)) % dim;
                v[dimIdx] += (float) Math.sin(trigram * 0.001 + j);
            }
            contributions++;
        }
        // L2 normalize
        if (contributions > 0) {
            double norm = 0;
            for (int i = 0; i < dim; i++) norm += v[i] * v[i];
            if (norm > 0) {
                norm = Math.sqrt(norm);
                for (int i = 0; i < dim; i++) v[i] /= (float) norm;
            }
        }
        return v;
    }
}