package io.github.yysf1949.rag.pipeline.ingest;

import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.model.IngestJobStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the in-memory {@link IngestJobRepositoryImpl}.
 */
class IngestJobRepositoryImplTest {

    @Test
    void saveAndFind() {
        IngestJobRepositoryImpl repo = new IngestJobRepositoryImpl();
        try {
            IngestJob job = IngestJob.newPending("t", "d");
            repo.save(job);
            Optional<IngestJob> got = repo.findById(job.jobId());
            assertTrue(got.isPresent());
            assertEquals(job.jobId(), got.get().jobId());
        } finally {
            repo.shutdown();
        }
    }

    @Test
    void findById_unknownReturnsEmpty() {
        IngestJobRepositoryImpl repo = new IngestJobRepositoryImpl();
        try {
            assertTrue(repo.findById("nope").isEmpty());
            assertTrue(repo.findById(null).isEmpty());
        } finally {
            repo.shutdown();
        }
    }

    @Test
    void deleteIdempotent() {
        IngestJobRepositoryImpl repo = new IngestJobRepositoryImpl();
        try {
            IngestJob job = IngestJob.newPending("t", "d");
            repo.save(job);
            repo.delete(job.jobId());
            assertTrue(repo.findById(job.jobId()).isEmpty());
            // Second delete must not throw.
            repo.delete(job.jobId());
        } finally {
            repo.shutdown();
        }
    }

    @Test
    void ttlEvictsOldJobs() throws Exception {
        // 50ms TTL with a 30ms sweeper period. Push a job, sleep past TTL +
        // sweeper, assert it's gone.
        IngestJobRepositoryImpl repo = new IngestJobRepositoryImpl(
                Duration.ofMillis(50),
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(),
                Duration.ofMillis(30));
        try {
            IngestJob job = IngestJob.newPending("t", "d");
            repo.save(job);
            // Wait long enough for TTL + at least one sweeper tick.
            Thread.sleep(200);
            assertTrue(repo.findById(job.jobId()).isEmpty(),
                    "job older than TTL should have been reaped");
        } finally {
            repo.shutdown();
        }
    }

    @Test
    void cannotConstructWithZeroOrNegativeTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> new IngestJobRepositoryImpl(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new IngestJobRepositoryImpl(Duration.ofSeconds(-1)));
    }

    @Test
    void statusTransitionsPreserveIdentity() {
        IngestJob job = IngestJob.newPending("t", "d");
        IngestJob processing = job.withStatus(IngestJobStatus.PROCESSING);
        IngestJob embedded = processing.withEmbeddedChunks(42);
        assertEquals(job.jobId(), processing.jobId());
        assertEquals(job.jobId(), embedded.jobId());
        assertEquals(IngestJobStatus.PROCESSING, processing.status());
        assertEquals(42, embedded.embeddedChunks());
    }
}
