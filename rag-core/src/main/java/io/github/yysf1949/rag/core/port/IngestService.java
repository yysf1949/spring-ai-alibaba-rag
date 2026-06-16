package io.github.yysf1949.rag.core.port;

import io.github.yysf1949.rag.core.model.Document;
import io.github.yysf1949.rag.core.model.IngestJob;

import java.util.Optional;

/**
 * Document ingestion entry point — design spec §6 + §10.
 *
 * <p>Drives a {@link Document} through the full pipeline:</p>
 * <ol>
 *   <li>{@code ChunkSplitter} — split into 200-800-token chunks (spec §6.2)</li>
 *   <li>{@link EmbeddingGateway} — batch-embed every chunk's content</li>
 *   <li>{@link VectorStore} — upsert into the staging index
 *       (status flips to {@code ACTIVE} on the explicit publish step)</li>
 * </ol>
 *
 * <p>Two flavours of entry point:</p>
 * <ul>
 *   <li>{@link #ingestSync} — runs on the caller's thread; useful for
 *       tests and small batch jobs where blocking is acceptable.</li>
 *   <li>{@link #ingestAsync} — submits to an internal executor pool and
 *       returns immediately with a {@code jobId}; status can be polled
 *       via {@link #getJob}.</li>
 * </ul>
 *
 * <p>Error semantics follow spec §10: a single chunk's failure is logged
 * and counted in {@code failedChunks} but does NOT abort the whole job.
 * The job is only {@code FAILED} when the splitter, embedding gateway,
 * or vector store blows up at the document level (no chunks produced,
 * no embeddings computed, no chunks written).</p>
 */
public interface IngestService {

    /**
     * Ingest synchronously — caller blocks until the document is fully
     * embedded and written to the staging index.
     *
     * @return the final {@link IngestJob} (status READY on success, FAILED on
     *         unrecoverable error)
     */
    IngestJob ingestSync(Document document);

    /**
     * Ingest asynchronously — returns immediately with a job in
     * {@code PENDING} state. Use {@link #getJob} to poll.
     *
     * @return the freshly-created job
     */
    IngestJob ingestAsync(Document document);

    /**
     * @return the current job snapshot, or empty if the id is unknown /
     *         has been GC'd
     */
    Optional<IngestJob> getJob(String jobId);

    /**
     * Promote a {@code READY} job to {@code PUBLISHED} — performs the
     * atomic index switch (spec §6.1) and updates the job status.
     */
    IngestJob publish(String jobId);
}
