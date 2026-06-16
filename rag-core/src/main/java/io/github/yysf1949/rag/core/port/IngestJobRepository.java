package io.github.yysf1949.rag.core.port;

import io.github.yysf1949.rag.core.model.IngestJob;

import java.util.Optional;

/**
 * Persistence layer for {@link IngestJob} state — design spec §6.3
 * "状态查询：GET /ingest/{jobId}".
 *
 * <p>Implementations MUST be thread-safe — the async executor and the
 * HTTP controller run on different threads and the same job can be
 * queried while it's in flight.</p>
 *
 * <p>Default TTL: implementations are free to evict jobs older than
 * {@code maxAgeHours} (default 24h, matching the answer cache TTL).</p>
 */
public interface IngestJobRepository {

    /**
     * Persist (or replace) the job snapshot. Returns the stored job.
     */
    IngestJob save(IngestJob job);

    /** @return the current snapshot, or empty if the id is unknown / evicted. */
    Optional<IngestJob> findById(String jobId);

    /** Evict a terminal-state job (idempotent). */
    void delete(String jobId);
}
