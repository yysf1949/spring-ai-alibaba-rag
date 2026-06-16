package io.github.yysf1949.rag.core.model;

import java.time.Instant;
import java.util.UUID;

/**
 * State of an asynchronous ingest job — design spec §6.3.
 *
 * <pre>
 *   PENDING ──► PROCESSING ──► READY ──► PUBLISHED
 *                  │            │
 *                  ▼            ▼
 *               FAILED       FAILED
 * </pre>
 *
 * <p>An ingest job is the unit of work for {@code IngestService} — one job
 * per document. The job carries the {@code jobId} (uuid), the originating
 * {@code documentId} for routing, lifecycle timestamps, counters
 * (totalChunks / embeddedChunks / upsertedChunks), and the eventual
 * error message if it failed.</p>
 *
 * <p>This is an immutable record: status transitions produce a new
 * instance via the {@code with*} factory methods, so {@code IngestJobRepository}
 * implementations can stash in-flight jobs in a thread-safe map without
 * locks.</p>
 *
 * @param jobId          unique ingest job id
 * @param tenantId       owning tenant (for routing & cleanup)
 * @param documentId     document this job is ingesting
 * @param status         current lifecycle state
 * @param totalChunks    expected chunk count (filled after splitting)
 * @param embeddedChunks number of chunks whose embedding has been computed
 * @param upsertedChunks number of chunks successfully written to vector store
 * @param failedChunks   number of chunks that failed in the pipeline
 *                       (spec §10 — failures are isolated per chunk)
 * @param createdAt      job creation timestamp
 * @param updatedAt      last status change timestamp
 * @param errorMessage   populated when {@code status=FAILED}; null otherwise
 */
public record IngestJob(
        String jobId,
        String tenantId,
        String documentId,
        IngestJobStatus status,
        int totalChunks,
        int embeddedChunks,
        int upsertedChunks,
        int failedChunks,
        Instant createdAt,
        Instant updatedAt,
        String errorMessage
) {

    public IngestJob {
        if (jobId == null || jobId.isBlank()) {
            jobId = UUID.randomUUID().toString();
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        if (status == null) {
            status = IngestJobStatus.PENDING;
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    /** Factory for a brand-new job in {@code PENDING} state. */
    public static IngestJob newPending(String tenantId, String documentId) {
        Instant now = Instant.now();
        return new IngestJob(
                UUID.randomUUID().toString(),
                tenantId,
                documentId,
                IngestJobStatus.PENDING,
                0, 0, 0, 0,
                now, now,
                null);
    }

    public IngestJob withStatus(IngestJobStatus newStatus) {
        return new IngestJob(jobId, tenantId, documentId, newStatus,
                totalChunks, embeddedChunks, upsertedChunks, failedChunks,
                createdAt, Instant.now(), errorMessage);
    }

    public IngestJob withTotalChunks(int total) {
        return new IngestJob(jobId, tenantId, documentId, status,
                total, embeddedChunks, upsertedChunks, failedChunks,
                createdAt, Instant.now(), errorMessage);
    }

    public IngestJob withEmbeddedChunks(int n) {
        return new IngestJob(jobId, tenantId, documentId, status,
                totalChunks, n, upsertedChunks, failedChunks,
                createdAt, Instant.now(), errorMessage);
    }

    public IngestJob withUpsertedChunks(int n) {
        return new IngestJob(jobId, tenantId, documentId, status,
                totalChunks, embeddedChunks, n, failedChunks,
                createdAt, Instant.now(), errorMessage);
    }

    public IngestJob withFailedChunks(int n) {
        return new IngestJob(jobId, tenantId, documentId, status,
                totalChunks, embeddedChunks, upsertedChunks, n,
                createdAt, Instant.now(), errorMessage);
    }

    public IngestJob withError(String message) {
        return new IngestJob(jobId, tenantId, documentId, status,
                totalChunks, embeddedChunks, upsertedChunks, failedChunks,
                createdAt, Instant.now(), message);
    }

    public boolean isTerminal() {
        return status == IngestJobStatus.PUBLISHED || status == IngestJobStatus.FAILED;
    }
}
