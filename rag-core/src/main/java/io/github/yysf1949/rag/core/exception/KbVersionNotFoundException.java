package io.github.yysf1949.rag.core.exception;

/**
 * The requested KB version does not exist. Distinct from
 * {@link KbNotFoundException} (the KB has never been published) — this one
 * is "you asked for v=42 but only v=1..3 exist".
 *
 * <p>Tool layer (e.g. {@code KbVersionTool}) catches this and returns a
 * structured error to the LLM so it can fall back to the active version
 * instead of failing the whole retrieval chain.</p>
 */
public class KbVersionNotFoundException extends RagException {

    public KbVersionNotFoundException(String message) {
        super(message);
    }
}