package io.github.yysf1949.rag.agent.builtin;

/**
 * Phase 19 — actions supported by {@link DocumentVersionTool}.
 *
 * <p>Mirrors {@link KbVersionAction} but adds an extra {@code docId}
 * dimension. Same idempotency story: PUBLISH and SWITCH/ROLLBACK are
 * idempotent.</p>
 */
public enum DocumentVersionAction {
    /** List all versions for a (tenant, kb, doc). */
    LIST,
    /** Query the currently-active version id for a doc. */
    GET_ACTIVE,
    /** Promote a specific version to ACTIVE (publish). Idempotent. */
    PUBLISH,
    /** Roll back to a previously-published version (semantically same as PUBLISH). Idempotent. */
    ROLLBACK
}
