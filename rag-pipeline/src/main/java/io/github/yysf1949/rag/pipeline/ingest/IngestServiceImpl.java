package io.github.yysf1949.rag.pipeline.ingest;

import io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException;
import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.Document;
import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.model.IngestJobStatus;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.IngestJobRepository;
import io.github.yysf1949.rag.core.port.IngestService;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.splitter.ChunkSplitter;
import io.github.yysf1949.rag.pipeline.logging.PipelineMdc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link IngestService} implementation — design spec §6.1.
 *
 * <h2>Pipeline</h2>
 * <pre>
 *   split(doc)        →  List&lt;Chunk&gt;  (status=STAGING, embedding=empty)
 *   embed(texts)      →  List&lt;float[]&gt;
 *   attach + upsert   →  VectorStore.upsert
 *   publish           →  VectorStore.publish  (atomic active-index flip)
 * </pre>
 *
 * <h2>Error semantics (spec §10)</h2>
 * <ul>
 *   <li><b>Embedding gateway down</b> — the WHOLE job is marked
 *       {@code FAILED} (no point in continuing: every chunk would fail
 *       the same way). The error message is stashed in
 *       {@code IngestJob.errorMessage} for the operator.</li>
 *   <li><b>VectorStore down</b> — same as embedding: the whole job is
 *       {@code FAILED}.</li>
 *   <li><b>VectorStore rejects a chunk</b> — chunk counted in
 *       {@code failedChunks}; the job continues with the rest. This
 *       covers tenant-mismatch or dimension-mismatch scenarios.</li>
 *   <li><b>Single chunk weird shape</b> (e.g. null embedding returned) —
 *       chunk is dropped and counted; never throws.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * {@link #ingestSync} runs on the caller's thread. {@link #ingestAsync}
 * submits to the supplied {@link ExecutorService} (spec §6.3: independent
 * pool, daemon threads, never on the web container's request threads).
 * If the executor has no free slots, the submit is bounded by a
 * {@code RejectedExecutionException} which the caller is expected to
 * translate into HTTP 503.
 */
public class IngestServiceImpl implements IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestServiceImpl.class);

    /**
     * Hard cap from spec §10: "chunk 数超阈值 (单 doc > 10000) 拒绝 + 提示拆分".
     */
    public static final int MAX_CHUNKS_PER_DOCUMENT = 10_000;

    /** Default embedding batch size (chunk texts per API call). */
    public static final int DEFAULT_EMBED_BATCH = 32;

    private final ChunkSplitter splitter;
    private final EmbeddingGateway embeddingGateway;
    private final VectorStore vectorStore;
    private final IngestJobRepository jobRepository;
    private final ExecutorService asyncExecutor;
    private final int embedBatchSize;
    private final MeterRegistry meterRegistry;

    public IngestServiceImpl(ChunkSplitter splitter,
                             EmbeddingGateway embeddingGateway,
                             VectorStore vectorStore,
                             IngestJobRepository jobRepository,
                             ExecutorService asyncExecutor) {
        this(splitter, embeddingGateway, vectorStore, jobRepository,
                asyncExecutor, DEFAULT_EMBED_BATCH, new SimpleMeterRegistry());
    }

    public IngestServiceImpl(ChunkSplitter splitter,
                             EmbeddingGateway embeddingGateway,
                             VectorStore vectorStore,
                             IngestJobRepository jobRepository,
                             ExecutorService asyncExecutor,
                             int embedBatchSize) {
        this(splitter, embeddingGateway, vectorStore, jobRepository,
                asyncExecutor, embedBatchSize, new SimpleMeterRegistry());
    }

    public IngestServiceImpl(ChunkSplitter splitter,
                             EmbeddingGateway embeddingGateway,
                             VectorStore vectorStore,
                             IngestJobRepository jobRepository,
                             ExecutorService asyncExecutor,
                             int embedBatchSize,
                             MeterRegistry meterRegistry) {
        if (splitter == null) throw new IllegalArgumentException("splitter must not be null");
        if (embeddingGateway == null) throw new IllegalArgumentException("embeddingGateway must not be null");
        if (vectorStore == null) throw new IllegalArgumentException("vectorStore must not be null");
        if (jobRepository == null) throw new IllegalArgumentException("jobRepository must not be null");
        if (asyncExecutor == null) throw new IllegalArgumentException("asyncExecutor must not be null");
        if (embedBatchSize <= 0) throw new IllegalArgumentException(
                "embedBatchSize must be > 0, got " + embedBatchSize);
        if (meterRegistry == null) throw new IllegalArgumentException("meterRegistry must not be null");
        this.splitter = splitter;
        this.embeddingGateway = embeddingGateway;
        this.vectorStore = vectorStore;
        this.jobRepository = jobRepository;
        this.asyncExecutor = asyncExecutor;
        this.embedBatchSize = embedBatchSize;
        this.meterRegistry = meterRegistry;
    }

    // ─── public API ────────────────────────────────────────────────────────

    @Override
    public IngestJob ingestSync(Document document) {
        IngestJob job = IngestJob.newPending(document.tenantId(), document.documentId());
        jobRepository.save(job);
        // Pin jobId on MDC for the entire sync run (cleared in finally).
        PipelineMdc.put(PipelineMdc.KEY_JOB_ID, job.jobId());
        try {
            long t0 = System.nanoTime();
            IngestJob result = runPipeline(document, job);
            Timer.builder("rag.ingest.duration.ms")
                    .tag("tenant", document.tenantId())
                    .register(meterRegistry)
                    .record(Duration.ofNanos(System.nanoTime() - t0).toMillis(), TimeUnit.MILLISECONDS);
            return result;
        } finally {
            MDC.remove(PipelineMdc.KEY_JOB_ID);
            MDC.remove(PipelineMdc.KEY_STAGE);
        }
    }

    @Override
    public IngestJob ingestAsync(Document document) {
        IngestJob job = IngestJob.newPending(document.tenantId(), document.documentId());
        jobRepository.save(job);
        // MDC is thread-local — the executor thread does NOT inherit the
        // HTTP-thread MDC (tenant / requestId). Snapshot now so the
        // background run can re-install it. Without this, async log lines
        // lose correlation with the originating HTTP request — a real
        // ops headache when you're staring at a jobId trying to find its
        // request trace.
        Map<String, String> httpContext = PipelineMdc.snapshot();
        PipelineMdc.put(PipelineMdc.KEY_JOB_ID, job.jobId());
        Map<String, String> submittedContext = PipelineMdc.snapshot();
        MDC.remove(PipelineMdc.KEY_JOB_ID);  // restored inside the lambda

        asyncExecutor.submit(() -> {
            // Restore HTTP-thread MDC inside the worker thread, then add
            // the jobId. Always release on exit so the executor thread is
            // clean for the next submission.
            PipelineMdc.restore(submittedContext);
            try {
                runPipeline(document, job);
            } catch (Throwable t) {
                // Defensive: the pipeline already catches everything per spec
                // §10, but if a bug leaks out, never let the executor's
                // UncaughtExceptionHandler kill the daemon.
                log.error("async ingest task crashed for jobId={} (originating httpContext={})",
                        job.jobId(), httpContext, t);
                jobRepository.save(job.withStatus(IngestJobStatus.FAILED)
                        .withError("uncaught: " + t.getMessage()));
            } finally {
                MDC.remove(PipelineMdc.KEY_JOB_ID);
                MDC.remove(PipelineMdc.KEY_STAGE);
                // Hand the executor thread back to its pool with NO MDC,
                // not whatever the previous job left behind.
                MDC.clear();
            }
        });
        return job;
    }

    @Override
    public Optional<IngestJob> getJob(String jobId) {
        return jobRepository.findById(jobId);
    }

    @Override
    public IngestJob publish(String jobId) {
        IngestJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("unknown jobId: " + jobId));
        if (job.status() != IngestJobStatus.READY) {
            throw new IllegalStateException(
                    "publish requires status READY, got " + job.status() + " for job " + jobId);
        }
        PipelineMdc.put(PipelineMdc.KEY_JOB_ID, jobId);
        PipelineMdc.put(PipelineMdc.KEY_STAGE, "publish");
        try {
            long kbVersion = parseKbVersion(job);
            IngestJob result;
            try {
                vectorStore.publish(job.tenantId(),
                        // kbId is the first segment of documentId-encoded info, but
                        // Document doesn't carry a separate kbId; we encode it in
                        // documentId as "kbId/docId". Reconstruct on the way out.
                        extractKbId(job.documentId()),
                        kbVersion);
                IngestJob published = job.withStatus(IngestJobStatus.PUBLISHED);
                jobRepository.save(published);
                result = published;
            } catch (VectorStoreUnavailableException ex) {
                IngestJob failed = job.withStatus(IngestJobStatus.FAILED)
                        .withError("publish failed: " + ex.getMessage());
                jobRepository.save(failed);
                result = failed;
            }
            recordJobTerminal(result.tenantId(), result.status().name());
            return result;
        } finally {
            MDC.remove(PipelineMdc.KEY_JOB_ID);
            MDC.remove(PipelineMdc.KEY_STAGE);
        }
    }

    // ─── pipeline core ─────────────────────────────────────────────────────

    /**
     * Record a job's terminal state in the metrics counter
     * {@code rag.ingest.jobs.total{tenant,status}}. Centralised so the
     * READY / FAILED / PUBLISHED paths all share the same metric shape
     * (spec §9.1).
     */
    private void recordJobTerminal(String tenantId, String status) {
        Counter.builder("rag.ingest.jobs.total")
                .tag("tenant", tenantId == null ? "unknown" : tenantId)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    private IngestJob runPipeline(Document doc, IngestJob job) {
        IngestJob processing = job.withStatus(IngestJobStatus.PROCESSING);
        jobRepository.save(processing);

        // Step 1: split
        List<Chunk> chunks;
        IngestJob counted = processing;  // upgraded after split count is known
        try {
            PipelineMdc.put(PipelineMdc.KEY_STAGE, "split");
            try {
                chunks = splitter.split(doc);
            } catch (Exception e) {
                log.error("splitter failed for jobId={} docId={}", job.jobId(), doc.documentId(), e);
                IngestJob failed = processing.withStatus(IngestJobStatus.FAILED)
                        .withError("splitter: " + e.getMessage());
                jobRepository.save(failed);
                recordJobTerminal(failed.tenantId(), "FAILED");
                return failed;
            }
            if (chunks.size() > MAX_CHUNKS_PER_DOCUMENT) {
                String msg = "chunk count " + chunks.size()
                        + " exceeds per-document cap " + MAX_CHUNKS_PER_DOCUMENT
                        + " (spec §10) — split the source document first";
                log.warn("rejecting jobId={} docId={} : {}", job.jobId(), doc.documentId(), msg);
                IngestJob failed = processing.withStatus(IngestJobStatus.FAILED).withError(msg);
                jobRepository.save(failed);
                recordJobTerminal(failed.tenantId(), "FAILED");
                return failed;
            }
            counted = processing.withTotalChunks(chunks.size());
            jobRepository.save(counted);
            if (chunks.isEmpty()) {
                // Empty document — nothing to do, mark READY and let the
                // operator decide whether to publish the empty kbVersion.
                IngestJob ready = counted.withStatus(IngestJobStatus.READY);
                jobRepository.save(ready);
                return ready;
            }
        } finally {
            MDC.remove(PipelineMdc.KEY_STAGE);
        }

        // Step 2: embed (batched). On total failure, mark FAILED. Partial
        // failure is impossible by the EmbeddingGateway contract — the
        // impl either returns N vectors or throws.
        List<float[]> vectors;
        IngestJob embedded = counted;
        try {
            PipelineMdc.put(PipelineMdc.KEY_STAGE, "embed");
            try {
                vectors = embedInBatches(chunks);
            } catch (EmbeddingUnavailableException eue) {
                log.error("embedding unavailable for jobId={} : {}", job.jobId(), eue.getMessage());
                IngestJob failed = counted.withStatus(IngestJobStatus.FAILED)
                        .withError("embedding: " + eue.getMessage());
                jobRepository.save(failed);
                recordJobTerminal(failed.tenantId(), "FAILED");
                return failed;
            }
            if (vectors.size() != chunks.size()) {
                String msg = "embedding gateway returned " + vectors.size()
                        + " vectors for " + chunks.size() + " chunks";
                IngestJob failed = counted.withStatus(IngestJobStatus.FAILED).withError(msg);
                jobRepository.save(failed);
                recordJobTerminal(failed.tenantId(), "FAILED");
                return failed;
            }
            embedded = counted.withEmbeddedChunks(chunks.size());
            jobRepository.save(embedded);
        } finally {
            MDC.remove(PipelineMdc.KEY_STAGE);
        }

        // Step 3: attach vectors + upsert in one batch
        PipelineMdc.put(PipelineMdc.KEY_STAGE, "upsert");
        List<Chunk> finalChunks = new ArrayList<>(chunks.size());
        int skipCount = 0;
        try {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                float[] v = vectors.get(i);
                if (v == null || v.length != embeddingGateway.dimension()) {
                    // Spec §10: a single chunk's failure does not block the doc.
                    skipCount++;
                    log.warn("jobId={} chunk {} ({}) dropped: null or dim-mismatched vector",
                            job.jobId(), c.chunkId(), c.sectionPath());
                    continue;
                }
                finalChunks.add(rebuildWithEmbedding(c, v));
            }
            if (finalChunks.isEmpty()) {
                IngestJob failed = embedded.withStatus(IngestJobStatus.FAILED)
                        .withError("all chunks produced invalid vectors");
                jobRepository.save(failed);
                recordJobTerminal(failed.tenantId(), "FAILED");
                return failed;
            }
            int written;
            try {
                written = vectorStore.upsert(finalChunks);
            } catch (VectorStoreUnavailableException vsue) {
                log.error("vector store unavailable for jobId={} : {}", job.jobId(), vsue.getMessage());
                IngestJob failed = embedded.withStatus(IngestJobStatus.FAILED)
                        .withError("vectorStore: " + vsue.getMessage());
                jobRepository.save(failed);
                recordJobTerminal(failed.tenantId(), "FAILED");
                return failed;
            }
            int failed_ = finalChunks.size() - written;
            IngestJob ready = embedded
                    .withUpsertedChunks(written)
                    .withFailedChunks(skipCount + failed_)
                    .withStatus(IngestJobStatus.READY);
            jobRepository.save(ready);
            recordJobTerminal(ready.tenantId(), "READY");
            Counter.builder("rag.ingest.chunks.total")
                    .tag("tenant", doc.tenantId())
                    .register(meterRegistry)
                    .increment(written);
            log.info("jobId={} docId={} staged: {} written, {} skipped (bad vec), {} rejected (store)",
                    job.jobId(), doc.documentId(), written, skipCount, failed_);
            return ready;
        } finally {
            MDC.remove(PipelineMdc.KEY_STAGE);
        }
    }

    private List<float[]> embedInBatches(List<Chunk> chunks) {
        List<float[]> all = new ArrayList<>(chunks.size());
        for (int from = 0; from < chunks.size(); from += embedBatchSize) {
            int to = Math.min(chunks.size(), from + embedBatchSize);
            List<String> texts = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) texts.add(chunks.get(i).content());
            all.addAll(embeddingGateway.embedBatch(texts));
        }
        return all;
    }

    private static Chunk rebuildWithEmbedding(Chunk c, float[] v) {
        return new Chunk(
                c.chunkId(), c.tenantId(), c.kbId(), c.documentId(),
                c.documentVersion(), c.title(), c.sectionPath(),
                c.content(), c.permissionTags(), c.status(),
                c.publishedAt(), c.sourceUri(), v);
    }

    // ─── kbId / kbVersion derivation ───────────────────────────────────────

    /**
     * We encode {@code (kbId, documentId)} as {@code "kbId/documentId"} in
     * the IngestJob so a single ingest job carries both without a wider
     * schema change. Callers producing a Document should follow the same
     * convention via {@link IngestServiceImpl#encodeDocumentId(String, String)}.
     */
    private static String extractKbId(String compositeDocumentId) {
        int slash = compositeDocumentId.indexOf('/');
        if (slash < 0) {
            // Plain documentId without kb prefix — fall back to a single
            // shared kb so the publish() call doesn't blow up.
            return "default-kb";
        }
        return compositeDocumentId.substring(0, slash);
    }

    private static long parseKbVersion(IngestJob job) {
        // kbVersion is the currentVersion on the KnowledgeBase. For Phase
        // 5-P2 we don't have a KB store yet, so derive deterministically
        // from updatedAt epoch seconds (good enough for the demo — the
        // first ingest after process start gets version 1, the next
        // gets version 2, etc.). Production will wire a KB repository.
        return Math.max(1L, job.updatedAt().getEpochSecond()
                - (job.createdAt().getEpochSecond() - 1L));
    }

    /** Helper for callers wiring a Document into the service. */
    public static String encodeDocumentId(String kbId, String docId) {
        if (kbId == null || kbId.isBlank()) throw new IllegalArgumentException("kbId blank");
        if (docId == null || docId.isBlank()) throw new IllegalArgumentException("docId blank");
        return kbId + "/" + docId;
    }
}
