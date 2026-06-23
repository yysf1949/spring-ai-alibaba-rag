package io.github.yysf1949.rag.core.model;

import java.time.Instant;

/**
 * Rich metadata for a single document version — what
 * {@link io.github.yysf1949.rag.core.port.DocumentVersionService} returns
 * from {@code listVersions}.
 *
 * <p>Distinct from {@link KbVersionMeta} (Phase 18 P2) by carrying the
 * additional {@code docId} dimension: this record is keyed by
 * {@code (tenantId, kbId, docId, versionId)} instead of
 * {@code (tenantId, kbId, versionId)}.</p>
 *
 * @param versionId    numeric version (matches {@code KbVersion} style)
 * @param docId        document identifier within the KB
 * @param status       one of {@link Status#DRAFT}, {@link Status#ACTIVE},
 *                     {@link Status#DEPRECATED}
 * @param createdAt    when the version was first created (ingest start)
 * @param publishedAt  when it was published; null if never published
 * @param chunkCount   number of chunks ingested into this version
 * @param sourceLabel  optional human-friendly label (e.g. "Q2-doc-drop",
 *                     git SHA, "manual-2026-06-18")
 */
public record DocumentVersionMeta(
        long versionId,
        String docId,
        Status status,
        Instant createdAt,
        Instant publishedAt,
        int chunkCount,
        String sourceLabel) {

    public enum Status {
        /** Created but no chunks ingested yet. */
        DRAFT,
        /** Live — retrieval resolves to this version when callers pass {@code -1}. */
        ACTIVE,
        /** Previously active but superseded; chunks are read-only, scheduled for GC. */
        DEPRECATED
    }

    public DocumentVersionMeta {
        if (versionId < 0) {
            throw new IllegalArgumentException("versionId must be non-negative, got " + versionId);
        }
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }
}
