package io.github.yysf1949.rag.core.port;

import io.github.yysf1949.rag.core.model.Chunk;

import java.util.List;

/**
 * Side-effects contract for the vector storage layer — implemented in
 * {@code rag-redis} (Redis Stack + RediSearch HNSW).
 *
 * <p>Design spec §4, §12 — every operation MUST apply the
 * {@code (tenantId, kbId, kbVersion, status=ACTIVE, permissionTags)} filter
 * server-side. Tenants are a hard wall — implementations must reject
 * cross-tenant writes at the index boundary, not the application boundary.</p>
 */
public interface VectorStore {

    /**
     * Upsert chunks into a versioned index. Implementations should route to:
     * <ul>
     *   <li>{@code rag:index:{tenant}:{kbVersion}-staging} when chunk status is STAGING</li>
     *   <li>{@code rag:index:{tenant}:{kbVersion}} otherwise</li>
     * </ul>
     *
     * @return number of chunks actually written (some stores may de-dup on chunkId)
     */
    int upsert(List<Chunk> chunks);

    /**
     * Delete chunks by id within a specific kbVersion.
     */
    int deleteByIds(String tenantId, String kbId, long kbVersion, List<String> chunkIds);

    /**
     * Vector search over the ACTIVE pool of one kbVersion.
     *
     * @param queryVector       embedding of the (rewritten) query
     * @param tenantId          hard wall
     * @param kbId              which KB
     * @param kbVersion         which version of that KB
     * @param userPermissionTags filter applied per {@code PermissionMode}
     * @param permissionMode    AND or OR (default AND per spec §8.2)
     * @param topK              size of the candidate pool (spec §8.1 default 20)
     * @return chunks ordered by descending similarity
     */
    List<Chunk> search(
            float[] queryVector,
            String tenantId,
            String kbId,
            long kbVersion,
            List<String> userPermissionTags,
            io.github.yysf1949.rag.core.model.PermissionMode permissionMode,
            int topK
    );

    /**
     * Atomically publish a staging index: copy all ACTIVE-flagged chunks from
     * {@code rag:index:{tenant}:{kbVersion}-staging} into
     * {@code rag:index:{tenant}:{kbVersion}} and update
     * {@code rag:publish:{tenant}:{kbId}} ⇒ {@code kbVersion}.
     *
     * <p>Design spec §6.1 step "原子切换".</p>
     */
    void publish(String tenantId, String kbId, long kbVersion);

    /**
     * Mark all chunks in a previous kbVersion as {@code DEPRECATED}. GC happens
     * elsewhere (cleanup job, 7 days later per spec §6.1).
     */
    int deprecate(String tenantId, String kbId, long oldKbVersion);

    /**
     * Delete all chunks belonging to a specific document within a kbVersion.
     *
     * <p>Used by partial re-index: before re-ingesting a single document, its
     * old chunks must be removed from the index so the new chunks replace them
     * cleanly. Other documents in the same kbVersion are untouched.</p>
     *
     * @param tenantId   hard wall
     * @param kbId       which KB
     * @param documentId which document's chunks to remove
     * @param kbVersion  which version of that KB
     * @return number of chunks actually deleted
     */
    int deleteByDocumentId(String tenantId, String kbId, String documentId, long kbVersion);
}