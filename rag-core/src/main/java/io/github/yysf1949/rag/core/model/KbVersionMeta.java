package io.github.yysf1949.rag.core.model;

import java.time.Instant;

/**
 * Rich metadata for a single KB version — what {@link io.github.yysf1949.rag.core.port.KbVersionService}
 * returns from {@code listVersions}.
 *
 * <p>Distinguished from the bare {@link KbVersion} data class on purpose:
 * the former carries identity ({@code tenantId, kbId, version}), while this
 * carries <em>observability</em> data — when the version was created, whether
 * it has been published, and how many chunks it currently holds.</p>
 *
 * @param versionId    numeric version (matches {@link KbVersion#version()})
 * @param status       one of {@link Status#STAGING}, {@link Status#ACTIVE},
 *                     {@link Status#DEPRECATED}, {@link Status#DRAFT}
 * @param createdAt    when the version was first created (ingest start)
 * @param publishedAt  when it was published; null if never published
 * @param docCount     number of chunks ingested into this version
 * @param sourceLabel  optional human-friendly label (e.g. "Q2-doc-drop", git SHA)
 */
public record KbVersionMeta(
        long versionId,
        Status status,
        Instant createdAt,
        Instant publishedAt,
        int docCount,
        String sourceLabel) {

    public enum Status {
        /** Created but no chunks ingested yet. */
        DRAFT,
        /** Chunks ingested into the staging pool, not yet live. */
        STAGING,
        /** Live — retrieval resolves to this version when callers pass {@code -1}. */
        ACTIVE,
        /** Previously active but superseded; chunks are read-only, scheduled for GC. */
        DEPRECATED
    }

    public KbVersionMeta {
        if (versionId < 0) {
            throw new IllegalArgumentException("versionId must be non-negative, got " + versionId);
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }
}