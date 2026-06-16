package io.github.yysf1949.rag.core.port;

import io.github.yysf1949.rag.core.model.Answer;

import java.util.Optional;

/**
 * Final-answer cache — design spec §7.1 + §13.6.
 *
 * <p>The QA chain consults this cache first; on a hit it short-circuits the
 * whole retrieve → rerank → LLM chain and returns the cached {@link Answer}
 * with {@code source == AnswerSource.CACHE}. The key incorporates
 * {@code tenantId + queryHash}, so cross-tenant leakage is structurally
 * impossible.</p>
 *
 * <p>Implementations must:</p>
 * <ul>
 *   <li>Apply a TTL (spec §13.6 default 24h, override via config).</li>
 *   <li>Never throw on read miss — return {@link Optional#empty()}.</li>
 *   <li>Best-effort on write — failures should not break the QA chain (a
 *       cached write is an optimization, not the source of truth).</li>
 * </ul>
 */
public interface AnswerCache {

    /**
     * @return the cached answer, or empty on miss / parse failure / backend error.
     */
    Optional<Answer> get(String tenantId, String queryHash);

    /**
     * Best-effort store. Returned boolean lets callers decide whether to
     * emit a metric on cache-write failure.
     */
    boolean put(String tenantId, String queryHash, Answer answer);

    /**
     * Invalidate every cached answer for a tenant — typically called from
     * the publish / deprecate path so stale answers stop being served.
     */
    long invalidateTenant(String tenantId);

    /**
     * @return current hit ratio [0,1] for the given tenant since process start.
     *         Implementations are free to return 0 if they don't track this.
     */
    default double hitRatio(String tenantId) {
        return 0.0;
    }
}
