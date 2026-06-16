package io.github.yysf1949.rag.core.exception;

/**
 * Base class for all RAG-domain exceptions. Callers should be able to catch
 * this single type and decide whether the failure is recoverable, retriable,
 * or fatal.
 *
 * <p>Design spec §10 — table of expected exceptions and their handling.</p>
 */
public abstract class RagException extends RuntimeException {

    protected RagException(String message) {
        super(message);
    }

    protected RagException(String message, Throwable cause) {
        super(message, cause);
    }
}