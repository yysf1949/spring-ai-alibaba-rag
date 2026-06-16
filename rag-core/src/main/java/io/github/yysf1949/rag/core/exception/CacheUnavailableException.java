package io.github.yysf1949.rag.core.exception;

/**
 * A cache backend (Redis) is unreachable or returned an unrecoverable error.
 *
 * <p>Design spec §10 — cache failures must NOT block the QA chain; callers
 * should log a warning and proceed to the upstream (LLM, embedding service,
 * …) as if the cache had simply missed. The {@code CacheUnavailableException}
 * is therefore surfaced only when the caller explicitly asks for failure
 * surfacing (e.g. health checks, metrics dashboards).</p>
 */
public class CacheUnavailableException extends RagException {

    public CacheUnavailableException(String message) {
        super(message);
    }

    public CacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
