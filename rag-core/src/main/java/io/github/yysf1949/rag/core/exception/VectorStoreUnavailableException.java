package io.github.yysf1949.rag.core.exception;

/**
 * The vector store (Redis Stack + RediSearch) is unreachable or returned an
 * unrecoverable error.
 *
 * <p>Design spec §10 row 3 — the QA endpoint surfaces this as HTTP 503
 * with {@code Retry-After}. Lettuce's auto-reconnect + circuit breaker
 * (30s) is the primary recovery path.</p>
 */
public class VectorStoreUnavailableException extends RagException {

    public VectorStoreUnavailableException(String message) {
        super(message);
    }

    public VectorStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}