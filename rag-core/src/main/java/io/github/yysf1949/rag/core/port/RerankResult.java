package io.github.yysf1949.rag.core.port;

import io.github.yysf1949.rag.core.model.Chunk;

/**
 * A rerank service response item — pairs a {@link Chunk} with its
 * relevance score from the rerank model. The score is model-dependent
 * (e.g., SiliconFlow gte-rerank returns [0, 1]).
 */
public record RerankResult(Chunk chunk, double relevanceScore) {}
