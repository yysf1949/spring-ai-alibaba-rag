package io.github.yysf1949.rag.agent.builtin;

/**
 * Request shape for {@link KbVersionTool#manage}.
 *
 * <p>Top-level record — same defensive pattern as
 * {@link KbSearchRequest} (Phase 18 P0 fix).</p>
 *
 * @param action     the verb to perform
 * @param tenantId   owning tenant (required)
 * @param kbId       knowledge base id (required)
 * @param versionId  target version for {@code SWITCH} / {@code ROLLBACK}; ignored
 *                   for {@code LIST} / {@code GET_ACTIVE}
 */
public record KbVersionRequest(
        KbVersionAction action,
        String tenantId,
        String kbId,
        Long versionId) {

    public KbVersionRequest {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId must not be blank");
        }
        if (versionId != null && versionId < 0) {
            throw new IllegalArgumentException("versionId must be non-negative, got " + versionId);
        }
    }
}