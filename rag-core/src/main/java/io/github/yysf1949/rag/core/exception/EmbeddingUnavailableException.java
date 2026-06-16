package io.github.yysf1949.rag.core.exception;

/**
 * The embedding provider (DashScope) is unavailable after retries.
 *
 * <p>Design spec §10 row 1 — caller (typically {@code QAService}) catches this
 * and downgrades the answer to {@link io.github.yysf1949.rag.core.model.AnswerSource#FALLBACK_RULE}
 * if at least the retrieval step succeeded; otherwise returns 503.</p>
 */
public class EmbeddingUnavailableException extends RagException {

    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}