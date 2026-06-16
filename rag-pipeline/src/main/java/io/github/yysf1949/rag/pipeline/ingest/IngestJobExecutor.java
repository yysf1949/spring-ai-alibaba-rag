package io.github.yysf1949.rag.pipeline.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds the dedicated executor pool used by {@code IngestServiceImpl}
 * for async ingest jobs — design spec §6.3.
 *
 * <p>Distinct from the web request thread pool, so a slow embedding
 * call can't block online QA traffic. Defaults:</p>
 * <ul>
 *   <li>corePoolSize = 2 — small footprint; ingest is not hot path</li>
 *   <li>maxPoolSize  = 8 — bursty headroom when the operator kicks off
 *       several large jobs back-to-back</li>
 *   <li>queue       = 64 — back-pressure point; when full,
 *       {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy}
 *       translates into a {@link RejectedExecutionException} that the
 *       controller surfaces as HTTP 503</li>
 *   <li>threads     = daemon — JVM shutdown is not blocked by in-flight
 *       ingest jobs</li>
 * </ul>
 */
public final class IngestJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(IngestJobExecutor.class);

    public static final int DEFAULT_CORE_POOL_SIZE = 2;
    public static final int DEFAULT_MAX_POOL_SIZE = 8;
    public static final int DEFAULT_QUEUE_CAPACITY = 64;
    public static final long DEFAULT_KEEP_ALIVE_SECONDS = 60L;

    private IngestJobExecutor() {
    }

    public static ExecutorService newDefaultExecutor() {
        return newExecutor(DEFAULT_CORE_POOL_SIZE, DEFAULT_MAX_POOL_SIZE,
                DEFAULT_QUEUE_CAPACITY, DEFAULT_KEEP_ALIVE_SECONDS);
    }

    public static ExecutorService newExecutor(int corePoolSize, int maxPoolSize,
                                               int queueCapacity, long keepAliveSeconds) {
        if (corePoolSize <= 0) throw new IllegalArgumentException("corePoolSize must be > 0");
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("maxPoolSize < corePoolSize");
        }
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity must be > 0");

        ThreadFactory factory = new ThreadFactory() {
            private final AtomicLong seq = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ingest-worker-" + seq.incrementAndGet());
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thread, ex) ->
                        log.error("uncaught exception in ingest thread {}", thread.getName(), ex));
                return t;
            }
        };

        return new ThreadPoolExecutor(
                corePoolSize, maxPoolSize,
                keepAliveSeconds, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
