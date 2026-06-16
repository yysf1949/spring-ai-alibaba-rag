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

    public static final int DIM = 16;

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
        // per-text fingerprint so cosine is well-defined across distinct texts
        int seed = text.hashCode();
        for (int i = 0; i < dim; i++) {
            v[i] = (float) Math.sin((seed + i) * 0.1);
        }
        return v;
    }
}