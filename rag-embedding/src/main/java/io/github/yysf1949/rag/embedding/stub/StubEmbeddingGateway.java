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
        // Per-dim fingerprint: every dimension sees the SAME text but a different
        // hash salt. This guarantees:
        //   (a) identical text → identical vector (cosine = 1)
        //   (b) any text change → different vector in many dims (random-ish cosine)
        // which is exactly what a deterministic stub needs to support rank-preserving
        // KNN retrieval. (Pre-change code used a single global seed, which collapsed
        // every dimension onto the same sin() phase and made KNN retrieval random.)
        // Use Java's String.hashCode() semantics (truncated to 32 bits) so the
        // Python test harness can reproduce — but the JVM is the production source
        // of truth, so tests align by recomputing with the same algorithm.
        for (int i = 0; i < dim; i++) {
            int seed = text.hashCode() ^ (i * 0x9E3779B9);
            v[i] = (float) Math.sin(seed * 0.0001);
        }
        return v;
    }
}