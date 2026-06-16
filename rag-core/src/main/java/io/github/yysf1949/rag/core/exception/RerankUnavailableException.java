package io.github.yysf1949.rag.core.exception;

/**
 * The reranker (DashScope gte-rerank) is unavailable.
 *
 * <p>Design spec §7.5 + §10 row 5 — caller skips the rerank step and uses
 * the top-K retrieval result directly (truncated to {@code min(topK, topN)}).
 * NOT a fatal error.</p>
 */
public class RerankUnavailableException extends RagException {

    public RerankUnavailableException(String message) {
        super(message);
    }

    public RerankUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}