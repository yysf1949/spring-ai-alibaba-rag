package io.github.yysf1949.rag.core.exception;

/**
 * Caller supplied input that cannot be processed — invalid KB version,
 * chunk text too large, missing permission tags, etc.
 *
 * <p>Maps to HTTP 400. Distinct from the other exceptions (which are
 * transient upstream failures).</p>
 */
public class InvalidQueryException extends RagException {

    public InvalidQueryException(String message) {
        super(message);
    }
}