package io.github.yysf1949.rag.agent.builtin;

/**
 * Action verb for {@link KbVersionTool} requests. Top-level enum — keeps
 * the action names stable across JSON serialisation (Spring AI 1.0.9
 * JsonParser round-trips enums by {@code name()}).
 */
public enum KbVersionAction {
    /** List all versions of a KB. */
    LIST,
    /** Return the currently-active version id. */
    GET_ACTIVE,
    /** Publish a specific version, making it the active one. */
    SWITCH,
    /** Re-publish a previous version (rollback). */
    ROLLBACK
}