package io.github.yysf1949.rag.core.model;

/**
 * Pinpointed knowledge-base version for retrieval isolation.
 *
 * <p>Design spec §8.1, §10.3 — every retrieval call carries a {@code KbVersion}
 * so that a publish-time flip never causes a half-built kbVersion to leak into
 * production search results.</p>
 *
 * @param tenantId   owning tenant
 * @param kbId       knowledge base id
 * @param version    numeric version, monotonic per (tenantId, kbId)
 */
public record KbVersion(
        String tenantId,
        String kbId,
        long version
) {
    public KbVersion {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId must not be blank");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative, got " + version);
        }
    }
}