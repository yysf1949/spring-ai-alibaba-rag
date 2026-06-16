package io.github.yysf1949.rag.embedding.stub;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.port.RerankService;

import java.util.List;

/**
 * Stub rerank — returns the first {@code topN} of the input, no actual
 * scoring. Matches the "degrade to input order" semantics prescribed in
 * {@link RerankService} Javadoc when an upstream model is unavailable.
 *
 * <p>Real impl: {@code io.github.yysf1949.rag.embedding.dashscope.DashScopeRerankService}
 * (Phase 5-P4, spec §11.3, §13.8).</p>
 */
public class StubRerankService implements RerankService {

    @Override
    public List<Chunk> rerank(String query, List<Chunk> candidates, int topN) {
        if (candidates.isEmpty()) return List.of();
        return candidates.subList(0, Math.min(topN, candidates.size()));
    }
}