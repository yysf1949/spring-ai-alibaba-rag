package io.github.yysf1949.rag.core.port;

import java.util.List;

/**
 * Hot-question provider — design spec §7.5.
 *
 * <p>When retrieval returns zero chunks (e.g. knowledge base is empty for
 * this tenant, or the query is way off-topic), the {@code QAService}
 * returns a graceful "I don't know, but you might ask…" message with a
 * list of recent popular questions. This port produces that list.</p>
 *
 * <h2>Default impl</h2>
 * The default implementation reads the most recent N entries from the
 * rewrite cache (which records every query the system has seen). Production
 * can swap in a real analytics-backed implementation — Redis ZSET by
 * frequency, ES top-K, etc.
 */
public interface HotQuestionProvider {

    /**
     * @param tenantId scope to one tenant — never cross tenants.
     * @param limit    max questions to return (typically 3-5).
     * @return list of recent questions, most recent first; may be empty.
     */
    List<String> recent(String tenantId, int limit);
}
