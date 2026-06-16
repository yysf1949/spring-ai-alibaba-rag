package io.github.yysf1949.rag.core.exception;

/**
 * The LLM (DashScope qwen-plus / qwen-max) call failed or timed out.
 *
 * <p>Design spec §7.5 + §10 row 2 — caller downgrades to
 * {@link io.github.yysf1949.rag.core.model.AnswerSource#FALLBACK_RULE} and
 * concatenates the retrieved chunks verbatim.</p>
 */
public class LlmUnavailableException extends RagException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}