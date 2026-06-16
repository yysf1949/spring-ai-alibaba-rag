package io.github.yysf1949.rag.core.port;

import io.github.yysf1949.rag.core.port.RewriteService.RewriteResult;

/**
 * Query-rewrite result cache — design spec §11.2 + §13.8.
 *
 * <p>Stores the result of {@link RewriteService#rewrite} so identical
 * (tenantId, queryHash) pairs skip the rule chain + optional LLM fallback
 * entirely. Like {@link AnswerCache}, the key incorporates tenantId
 * explicitly, so cross-tenant leakage is structurally impossible.</p>
 *
 * <p>TTLs are short (spec §13.8 default 6h) because rewrite rules /
 * synonym tables can change more frequently than the underlying knowledge
 * base.</p>
 */
public interface RewriteCache {

    /** @return cached rewrite, or null on miss / backend error. */
    RewriteResult get(String tenantId, String queryHash);

    /** Best-effort store. */
    boolean put(String tenantId, String queryHash, RewriteResult result);

    /** @return hit ratio [0,1] for the given tenant since process start. */
    default double hitRatio(String tenantId) {
        return 0.0;
    }
}
