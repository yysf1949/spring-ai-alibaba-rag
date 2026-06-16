package io.github.yysf1949.rag.core.model;

/**
 * Lifecycle status of a chunk within an ingest / publish cycle.
 *
 * <p>Design spec §6.1 + §10 — chunks travel through:
 * {@code STAGING → ACTIVE → DEPRECATED}.</p>
 *
 * <pre>
 *   STAGING    just ingested, written to a side index, awaiting validation
 *   ACTIVE     promoted by {@code rag:publish:{tenant}:{kbId}} flip
 *   DEPRECATED superseded by a newer kbVersion; kept for 7 days then GC'd
 * </pre>
 */
public enum ChunkStatus {
    STAGING,
    ACTIVE,
    DEPRECATED
}