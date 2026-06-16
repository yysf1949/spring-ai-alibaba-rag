package io.github.yysf1949.rag.pipeline.ingest;

import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.port.IngestJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory {@link IngestJobRepository} with TTL eviction.
 *
 * <p>Used as the default {@code IngestService} backend so the engine
 * can run without an external store. Jobs older than {@code ttl}
 * (default 24h) are reaped by a single daemon thread. The map is a
 * {@link ConcurrentHashMap} so put / get are lock-free on the hot path.</p>
 *
 * <p>For production multi-instance deployments, swap in a Redis-backed
 * implementation; the port interface is designed to allow this without
 * changing the {@code IngestService} API.</p>
 */
public class IngestJobRepositoryImpl implements IngestJobRepository {

    private static final Logger log = LoggerFactory.getLogger(IngestJobRepositoryImpl.class);

    /** Default TTL — matches the answer cache default. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /** Default reaper period — hourly for the production default. */
    public static final Duration DEFAULT_SWEEP_PERIOD = Duration.ofHours(1);

    private final Map<String, IngestJob> store = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final ScheduledExecutorService sweeper;

    public IngestJobRepositoryImpl() {
        this(DEFAULT_TTL);
    }

    public IngestJobRepositoryImpl(Duration ttl) {
        this(ttl, buildSweeper(), DEFAULT_SWEEP_PERIOD);
    }

    public IngestJobRepositoryImpl(Duration ttl,
                                   ScheduledExecutorService sweeper,
                                   Duration sweepPeriod) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        if (sweepPeriod == null || sweepPeriod.isZero() || sweepPeriod.isNegative()) {
            throw new IllegalArgumentException("sweepPeriod must be positive, got " + sweepPeriod);
        }
        this.ttl = ttl;
        this.sweeper = sweeper;
        if (this.sweeper != null) {
            this.sweeper.scheduleAtFixedRate(this::sweep,
                    sweepPeriod.toMillis(), sweepPeriod.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public IngestJob save(IngestJob job) {
        if (job == null) throw new IllegalArgumentException("job must not be null");
        store.put(job.jobId(), job);
        return job;
    }

    @Override
    public Optional<IngestJob> findById(String jobId) {
        if (jobId == null) return Optional.empty();
        return Optional.ofNullable(store.get(jobId));
    }

    @Override
    public void delete(String jobId) {
        if (jobId != null) store.remove(jobId);
    }

    /** Stop the background reaper. Idempotent. */
    public void shutdown() {
        if (sweeper != null && !sweeper.isShutdown()) {
            sweeper.shutdownNow();
        }
    }

    // ─── internals ─────────────────────────────────────────────────────────

    private void sweep() {
        Instant cutoff = Instant.now().minus(ttl);
        int before = store.size();
        store.entrySet().removeIf(e -> e.getValue().updatedAt().isBefore(cutoff));
        int after = store.size();
        if (before != after) {
            log.info("IngestJobRepository sweep: {} → {} jobs (TTL={})", before, after, ttl);
        }
    }

    private static ScheduledExecutorService buildSweeper() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ingest-job-sweeper");
            t.setDaemon(true);
            return t;
        });
    }

    /** Visible for tests. */
    int size() {
        return store.size();
    }
}
