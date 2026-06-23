package io.github.yysf1949.rag.agent.builtin;

/**
 * Phase 19 — request payload for {@link DocumentVersionTool}.
 *
 * <p>Top-level record on purpose (avoids the P0 record-inner-class Spring AI
 * 1.0.9 deserialization bug — see {@code KbSearchRequest}).</p>
 *
 * @param action      one of {@link DocumentVersionAction}
 * @param tenantId    non-blank owning tenant
 * @param kbId        non-blank KB identifier
 * @param docId       non-blank document identifier (within the KB)
 * @param versionId   required for PUBLISH/ROLLBACK; ignored for LIST/GET_ACTIVE
 * @param sourceLabel optional human-friendly label for PUBLISH
 */
public record DocumentVersionRequest(
        DocumentVersionAction action,
        String tenantId,
        String kbId,
        String docId,
        Long versionId,
        String sourceLabel) {

    public DocumentVersionRequest {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId must not be blank");
        }
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be blank");
        }
    }
}
