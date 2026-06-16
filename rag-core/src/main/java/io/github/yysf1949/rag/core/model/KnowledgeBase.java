package io.github.yysf1949.rag.core.model;

import java.time.Instant;
import java.util.Set;

/**
 * Logical knowledge base — a curated collection of chunks share tenant + kb.
 *
 * <p>Design spec §6, §8 — a KB is owned by a tenant, can be sliced by
 * {@code permissionTags}, and is versioned via {@link KbVersion}.</p>
 *
 * @param kbId            unique within a tenant
 * @param tenantId        owning tenant
 * @param displayName     human-readable name
 * @param description     optional summary
 * @param permissionTags  tags inherited by all chunks unless overridden
 * @param currentVersion  currently active version (driven by publish flip)
 * @param createdAt
 * @param updatedAt
 */
public record KnowledgeBase(
        String kbId,
        String tenantId,
        String displayName,
        String description,
        Set<String> permissionTags,
        long currentVersion,
        Instant createdAt,
        Instant updatedAt
) {

    public KnowledgeBase {
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId must not be blank");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        permissionTags = permissionTags == null ? Set.of() : Set.copyOf(permissionTags);
        if (currentVersion < 0) {
            throw new IllegalArgumentException("currentVersion must be non-negative");
        }
    }

    public KbVersion currentKbVersion() {
        return new KbVersion(tenantId, kbId, currentVersion);
    }
}