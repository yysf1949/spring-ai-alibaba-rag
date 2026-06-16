package io.github.yysf1949.rag.pipeline.logging;

import org.slf4j.MDC;

import java.util.Map;

/**
 * Centralised MDC key constants + a small {@link #withStage(String, Runnable)}
 * helper that puts a {@code stage} value into the SLF4J {@link MDC}, runs the
 * action, then clears it — even on exception.
 *
 * <p>Why a helper instead of every site calling {@code MDC.put} + {@code MDC.remove}
 * by hand? Two reasons:
 * <ol>
 *   <li><b>No leakage on early returns / exceptions.</b> A bare {@code MDC.put} +
 *       {@code return ...} without {@code finally} leaks the stage into the
 *       caller's MDC (especially nasty for the cache-HIT and rerank-fallback
 *       branches in QAServiceImpl).</li>
 *   <li><b>Same key everywhere.</b> {@code "stage"}, {@code "tenant"},
 *       {@code "requestId"}, {@code "jobId"}, {@code "queryHash"} are spelled
 *       in exactly one place — the logback {@code %X{...}} pattern reads
 *       from these, and a typo here means the pattern renders blank.</li>
 * </ol>
 *
 * <p>The HTTP-layer {@code MdcTenantFilter} (in {@code rag-app}) populates
 * {@code tenant} + {@code requestId} at the request boundary; the pipeline
 * adds {@code stage} and (for ingest) {@code jobId} / {@code queryHash}.
 */
public final class PipelineMdc {

    public static final String KEY_TENANT = "tenant";
    public static final String KEY_REQUEST_ID = "requestId";
    public static final String KEY_JOB_ID = "jobId";
    public static final String KEY_QUERY_HASH = "queryHash";
    public static final String KEY_STAGE = "stage";

    private PipelineMdc() {}

    /**
     * Run {@code action} with {@code stage} in the MDC; clear it on the way
     * out (success OR exception). Use {@link Runnable} for void work and
     * wrap any return value yourself; we want to avoid leaking checked
     * exception types into this helper.
     */
    public static void withStage(String stage, Runnable action) {
        MDC.put(KEY_STAGE, stage);
        try {
            action.run();
        } finally {
            MDC.remove(KEY_STAGE);
        }
    }

    /**
     * Snapshot the current MDC into a plain {@link Map} so an async task
     * (e.g. the {@code IngestServiceImpl} async executor) can re-install
     * the HTTP-thread context. Returns a defensive copy — clearing the
     * live MDC after the snapshot does NOT mutate the returned map.
     */
    public static Map<String, String> snapshot() {
        Map<String, String> ctx = MDC.getCopyOfContextMap();
        return ctx == null ? java.util.Collections.emptyMap() : ctx;
    }

    /** Re-install a previously captured MDC snapshot (used by async tasks). */
    public static void restore(Map<String, String> ctx) {
        MDC.clear();
        if (ctx != null) {
            ctx.forEach(MDC::put);
        }
    }

    /** Convenience: put a single key, return the old value (may be null). */
    public static String put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
            return MDC.get(key);
        }
        MDC.put(key, value);
        return MDC.get(key);
    }
}