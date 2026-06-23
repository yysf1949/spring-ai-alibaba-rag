package io.github.yysf1949.rag.core.port;

import io.github.yysf1949.rag.core.model.DocumentVersionMeta;

import java.util.List;
import java.util.Optional;

/**
 * Phase 19 — Document-level version lifecycle service.
 *
 * <h2>Why a separate port (not {@link KbVersionService} with extra arg)</h2>
 * <p>{@link KbVersionService} already exists in the port for whole-KB
 * versioning (Phase 18 P2). But:
 * <ul>
 *   <li>Document-level versioning has a different cardinality (KB × doc
 *       instead of KB) and a different primary-key shape
 *       {@code (tenant, kb, doc, version)} vs
 *       {@code (tenant, kb, version)}.</li>
 *   <li>Agent-side tools and REST controllers need cross-backend
 *       {@code list / getActive / publish / rollback} per doc with
 *       consistent semantics.</li>
 *   <li>Document rollback is independent of KB rollback: rolling back
 *       a doc does not touch the other docs in the KB. Keeping it on a
 *       dedicated port makes that isolation explicit.</li>
 * </ul>
 *
 * <h2>Interaction with {@link KbVersionService}</h2>
 * <p>{@link io.github.yysf1949.rag.pipeline.port.RetrievalAdapter} reads from
 * both ports. Resolution priority:
 * <ol>
 *   <li>Caller-supplied {@code Map<docId, versionId>} override</li>
 *   <li>This service ({@link #resolveVersion})</li>
 *   <li>Fallback to {@link KbVersionService#getActiveVersion}</li>
 *   <li>Fallback to {@code 0} (legacy, P1/P2 callers)</li>
 * </ol>
 *
 * <h2>Thread-safety</h2>
 * <p>Implementations must be safe under concurrent reads + writes.
 * The expected concurrency model is "one publish at a time, many reads";
 * a store-level lock (or row lock) is appropriate.</p>
 */
public interface DocumentVersionService {

    /**
     * List every version known to this service for a given (tenant, kb, doc),
     * newest first. Versions include DRAFT / ACTIVE / DEPRECATED — no
     * filtering.
     *
     * @param tenantId non-blank owning tenant
     * @param kbId     non-blank KB identifier
     * @param docId    non-blank document identifier
     * @return list (possibly empty); never null
     */
    List<DocumentVersionMeta> listVersions(String tenantId, String kbId, String docId);

    /**
     * @return the currently-active version id for the doc, or
     *         {@link Optional#empty()} if the doc has never been published.
     */
    Optional<Long> getActiveVersion(String tenantId, String kbId, String docId);

    /**
     * Atomically publish a doc version — make it the ACTIVE version of the
     * doc. If a different version is currently active, it becomes
     * {@link DocumentVersionMeta.Status#DEPRECATED}. Idempotent:
     * re-publishing the already-active version is a no-op.
     *
     * @throws io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException
     *         if the version does not exist
     */
    DocumentVersionMeta publish(String tenantId, String kbId, String docId, long versionId, String sourceLabel);

    /**
     * Roll back to a previously-published doc version (re-publish it). The
     * currently-active version becomes DEPRECATED.
     *
     * @throws io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException
     *         if either version does not exist
     */
    DocumentVersionMeta rollback(String tenantId, String kbId, String docId, long targetVersion);

    /**
     * Resolve a "client request" version id into a concrete version id.
     *
     * <p>Semantics:
     * <ul>
     *   <li>{@code requested < 0} → return the currently active version id,
     *       or throw
     *       {@link io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException}
     *       if there is none (doc has never been published).</li>
     *   <li>{@code requested >= 0} → return {@code requested} unchanged, or
     *       throw
     *       {@link io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException}
     *       if that version does not exist.</li>
     * </ul>
     */
    long resolveVersion(String tenantId, String kbId, String docId, long requested);

    /**
     * Register a new version row in the store. Idempotent: re-registering
     * the same (tenant, kb, doc, version) quadruple is a no-op (first
     * registration wins).
     *
     * <p>Typically called by the ingest pipeline after staging the doc
     * chunks: "the versionId=N is ready, register it as DRAFT".</p>
     */
    DocumentVersionMeta registerVersion(String tenantId, String kbId, String docId, long versionId,
                                        DocumentVersionMeta.Status initialStatus,
                                        String sourceLabel,
                                        int chunkCount);
}
