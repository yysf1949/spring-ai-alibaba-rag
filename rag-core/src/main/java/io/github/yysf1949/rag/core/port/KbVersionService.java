package io.github.yysf1949.rag.core.port;

import io.github.yysf1949.rag.core.model.KbVersionMeta;

import java.util.List;
import java.util.Optional;

/**
 * Phase 18 P2 — KB version lifecycle service.
 *
 * <h2>Why a separate port (not just {@link VectorStore})</h2>
 * <p>{@link VectorStore#publish} already exists in the port for the
 * single-backend case (Redis ships a publish path). But:
 * <ul>
 *   <li>Other backends (H2, MySQL, Jdbc) need a separate bookkeeping table —
 *       the {@code VectorStore} Port doesn't carry a way to enumerate versions
 *       or read the current active pointer.</li>
 *   <li>Agent-side tools and REST controllers need cross-backend
 *       {@code list / getActive / publish / rollback} with consistent
 *       semantics. Routing those through one interface keeps the call sites
 *       clean.</li>
 *   <li>{@link VectorStore} keeps its single-method {@code publish} for the
 *       existing data-plane (chunks promotion); {@link KbVersionService}
 *       manages the control-plane (which version is live, listing, rollback).
 *       Same idea as Kubernetes' spec/status separation.</li>
 * </ul>
 *
 * <h2>Active-version convention</h2>
 * <p>Callers ask "which version is live?" via
 * {@link #getActiveVersion(String, String)}; the service returns
 * {@code Optional.empty()} if the KB has never been published
 * ({@link io.github.yysf1949.rag.core.exception.KbNotFoundException} would be
 * appropriate too, but {@code Optional.empty()} is friendlier for the
 * retrieval flow which can fall back to a rule).</p>
 *
 * <h2>Publishing semantics</h2>
 * <ul>
 *   <li>{@link #publish(String, String, long)} is idempotent: re-publishing an
 *       already-active version is a no-op. Republishing after a rollback is
 *       what makes rollback useful.</li>
 *   <li>The previously-active version automatically becomes
 *       {@link KbVersionMeta.Status#DEPRECATED} — never silently deleted.
 *       GC is a separate concern (spec §6.1: 7-day retention).</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * <p>Implementations must be safe under concurrent reads + writes.
 * The expected concurrency model is "one publish at a time, many reads"; a
 * store-level lock (or row lock) is appropriate.</p>
 */
public interface KbVersionService {

    /**
     * List every version known to this service for a given KB, newest first.
     * Versions include DRAFT / STAGING / ACTIVE / DEPRECATED — no filtering.
     *
     * @param tenantId non-blank owning tenant
     * @param kbId     non-blank KB identifier
     * @return list (possibly empty); never null
     */
    List<KbVersionMeta> listVersions(String tenantId, String kbId);

    /**
     * @return the currently-active version id, or {@link Optional#empty()} if
     *         the KB has never been published.
     */
    Optional<Long> getActiveVersion(String tenantId, String kbId);

    /**
     * Atomically publish a version — make it the ACTIVE version of the KB.
     * If a different version is currently active, it becomes DEPRECATED.
     * Idempotent: re-publishing the already-active version is a no-op.
     *
     * @throws io.github.yysf1949.rag.core.exception.KbVersionNotFoundException
     *         if the version does not exist
     */
    void publish(String tenantId, String kbId, long versionId);

    /**
     * Roll back to a previously-published version (re-publish it). The
     * currently-active version becomes DEPRECATED. Rollback is the same as
     * {@link #publish} on an older version, but semantically distinct enough
     * to deserve its own name in agent-facing tools.
     *
     * @throws io.github.yysf1949.rag.core.exception.KbVersionNotFoundException
     *         if either version does not exist
     */
    void rollback(String tenantId, String kbId, long versionId);

    /**
     * Resolve a caller's {@code kbVersion} request to the actual version id
     * the {@link RetrievalPort} should search:
     * <ul>
     *   <li>{@code requested < 0} (e.g. {@code -1}) → active version, or
     *       {@link io.github.yysf1949.rag.core.exception.KbVersionNotFoundException}
     *       if the KB has never been published.</li>
     *   <li>{@code requested >= 0} → that specific version, or
     *       {@link io.github.yysf1949.rag.core.exception.KbVersionNotFoundException}
     *       if it doesn't exist.</li>
     * </ul>
     */
    long resolveVersion(String tenantId, String kbId, long requested);
}