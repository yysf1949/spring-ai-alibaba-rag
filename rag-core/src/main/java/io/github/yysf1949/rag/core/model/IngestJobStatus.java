package io.github.yysf1949.rag.core.model;

/**
 * Lifecycle status of an {@link IngestJob} — design spec §6.3.
 *
 * <pre>
 *   PENDING     job accepted, waiting for an executor slot
 *   PROCESSING  splitter / embedding / upsert in flight
 *   READY       all chunks written to staging index, awaiting operator
 *               verification (recall@K, citation coverage, sample QA)
 *   PUBLISHED   atomic flip done; chunks now in active index
 *   FAILED      unrecoverable error; see {@code errorMessage} for details
 * </pre>
 *
 * <p>The READY → PUBLISHED transition is gated by a human in the loop per
 * spec §6.1 ("灰度验证"). The default {@code IngestService.ingestAsync}
 * skips READY and goes directly to PUBLISHED for the demo; production
 * deployments should add an explicit "publish(jobId)" call after a
 * human-reviewed gate.</p>
 */
public enum IngestJobStatus {
    PENDING,
    PROCESSING,
    READY,
    PUBLISHED,
    FAILED
}
