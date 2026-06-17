package io.github.yysf1949.rag.embedding.siliconflow;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException;
import io.github.yysf1949.rag.core.port.EmbeddingCache;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * SiliconFlow {@code BAAI/bge-m3} embedding adapter (Phase 5-P4, spec §13.5).
 *
 * <p>Talks to the OpenAI-compatible {@code POST /v1/embeddings} endpoint at
 * {@link SiliconFlowProperties#getBaseUrl()}. Embeddings are 1024-dim by
 * default and match the Redis HNSW index dim configured in
 * {@code application.yml} ({@code spring.rag.embedding.dim=1024}).</p>
 *
 * <h2>Error &amp; degradation contract</h2>
 * <ul>
 *   <li>Retry: up to {@code maxRetries} with exponential backoff
 *       (1s → 3s) on transient errors (5xx, timeout, connection refused).</li>
 *   <li>4xx (bad input, auth failure) is treated as terminal — no retry,
 *       immediate {@link EmbeddingUnavailableException}.</li>
 *   <li>After retries exhausted, throws
 *       {@link EmbeddingUnavailableException}. The {@code QAService}
 *       catches this and downgrades to {@code FALLBACK_RULE}.</li>
 * </ul>
 *
 * <h2>Caching</h2>
 * Calls consult the {@link EmbeddingCache} first; misses go to SiliconFlow
 * and are written back. {@link #embedWithoutCache(List)} bypasses the
 * cache for warm-up / re-embedding jobs.
 *
 * <h2>Thread-safety</h2>
 * Stateless after construction — safe for concurrent use.
 */
public class SiliconFlowEmbeddingGateway implements EmbeddingGateway {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowEmbeddingGateway.class);

    /** Per-call backoff base. Production = 1s, tests can override via constructor. */
    private final Duration backoffMin;
    /** Per-call backoff cap. Production = 5s, tests can override via constructor. */
    private final Duration backoffMax;

    private final WebClient webClient;
    private final SiliconFlowProperties properties;
    private final EmbeddingCache cache; // may be null
    private final int dimension;
    private final MeterRegistry meterRegistry;
    private final CircuitBreaker circuitBreaker; // may be null in hermetic tests

    public SiliconFlowEmbeddingGateway(WebClient webClient,
                                       SiliconFlowProperties properties,
                                       EmbeddingCache cache) {
        this(webClient, properties, cache, Duration.ofSeconds(1), Duration.ofSeconds(5),
             new SimpleMeterRegistry(), null);
    }

    /**
     * Test-friendly constructor that exposes the retry backoff window so
     * hermetic unit tests don't have to wait for production-scale delays.
     */
    public SiliconFlowEmbeddingGateway(WebClient webClient,
                                       SiliconFlowProperties properties,
                                       EmbeddingCache cache,
                                       Duration backoffMin,
                                       Duration backoffMax) {
        this(webClient, properties, cache, backoffMin, backoffMax, new SimpleMeterRegistry(), null);
    }

    public SiliconFlowEmbeddingGateway(WebClient webClient,
                                       SiliconFlowProperties properties,
                                       EmbeddingCache cache,
                                       Duration backoffMin,
                                       Duration backoffMax,
                                       MeterRegistry meterRegistry) {
        this(webClient, properties, cache, backoffMin, backoffMax, meterRegistry, null);
    }

    /**
     * Production constructor — wires the {@code siliconflow} circuit breaker
     * from the auto-configured {@link CircuitBreakerRegistry}. The breaker
     * trips when SiliconFlow returns consecutive 5xx / timeouts (configured
     * in {@code application.yml} under {@code resilience4j.circuitbreaker.instances.siliconflow}).
     * While OPEN, calls short-circuit with {@link CallNotPermittedException}
     * which is mapped to {@link EmbeddingUnavailableException} — the same
     * type the retry-exhausted path throws, so the caller (QAService) can
     * degrade uniformly.
     */
    public SiliconFlowEmbeddingGateway(WebClient webClient,
                                       SiliconFlowProperties properties,
                                       EmbeddingCache cache,
                                       Duration backoffMin,
                                       Duration backoffMax,
                                       MeterRegistry meterRegistry,
                                       CircuitBreakerRegistry circuitBreakerRegistry) {
        this.webClient = webClient;
        this.properties = properties;
        this.cache = cache;
        this.dimension = properties.getEmbedding().getDimension();
        this.backoffMin = backoffMin;
        this.backoffMax = backoffMax;
        this.meterRegistry = meterRegistry;
        this.circuitBreaker = (circuitBreakerRegistry == null) ? null
                : circuitBreakerRegistry.circuitBreaker("siliconflow");
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        // 1. Consult cache per text. Build the miss list.
        List<String> missTexts = new ArrayList<>();
        List<Integer> missIndices = new ArrayList<>();
        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            results.add(null); // placeholder
            if (cache == null) {
                missTexts.add(texts.get(i));
                missIndices.add(i);
                continue;
            }
            String hash = sha256(texts.get(i));
            float[] hit = cache.get(hash);
            if (hit != null) {
                if (hit.length != dimension) {
                    log.warn("SiliconFlowEmbeddingGateway cache dim-mismatch hash={} cached={} expected={} — refetching",
                            hash, hit.length, dimension);
                    missTexts.add(texts.get(i));
                    missIndices.add(i);
                } else {
                    results.set(i, hit);
                }
            } else {
                missTexts.add(texts.get(i));
                missIndices.add(i);
            }
        }

        if (missTexts.isEmpty()) {
            log.debug("SiliconFlowEmbeddingGateway cache 100% hit on {} texts", texts.size());
            return results;
        }

        // 2. Fetch misses in one HTTP round-trip (OpenAI batch semantics).
        List<float[]> fetched;
        try {
            fetched = callEmbeddings(missTexts);
        } catch (EmbeddingUnavailableException ex) {
            // spec §10 — caller (QAService) catches and degrades to FALLBACK_RULE.
            throw ex;
        }

        // 3. Place fetched vectors into result + write back to cache.
        Map<String, float[]> warmup = new HashMap<>();
        for (int j = 0; j < missIndices.size(); j++) {
            int idx = missIndices.get(j);
            float[] vec = fetched.get(j);
            results.set(idx, vec);
            if (cache != null) {
                warmup.put(sha256(texts.get(idx)), vec);
            }
        }
        if (cache != null && !warmup.isEmpty()) {
            try {
                cache.putMany(warmup);
            } catch (RuntimeException ex) {
                // Cache failures must not break the read path (spec §13.7).
                log.warn("SiliconFlowEmbeddingGateway cache.putMany failed: {}", ex.toString());
            }
        }
        return results;
    }

    @Override
    public List<float[]> embedWithoutCache(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return callEmbeddings(texts);
    }

    @Override
    public void warmCache(Map<String, float[]> entries) {
        if (cache == null || entries == null || entries.isEmpty()) {
            return;
        }
        // Filter out dim-mismatched entries before writing (spec §13.7).
        Map<String, float[]> filtered = new HashMap<>();
        for (Map.Entry<String, float[]> e : entries.entrySet()) {
            if (e.getValue() != null && e.getValue().length == dimension) {
                filtered.put(e.getKey(), e.getValue());
            } else {
                log.warn("SiliconFlowEmbeddingGateway.warmCache skipping dim-mismatch key={} got={} expected={}",
                        e.getKey(),
                        e.getValue() == null ? "null" : e.getValue().length,
                        dimension);
            }
        }
        try {
            cache.putMany(filtered);
        } catch (RuntimeException ex) {
            log.warn("SiliconFlowEmbeddingGateway.warmCache putMany failed: {}", ex.toString());
        }
    }

    // ─── internal HTTP call with retry ───────────────────────────────

    private List<float[]> callEmbeddings(List<String> texts) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Wrap the HTTP call in a circuit breaker (when wired). While the
            // breaker is OPEN we never hit the WebClient — we short-circuit
            // straight to EmbeddingUnavailableException, sparing the upstream.
            // WebClient's own retryWhen still runs INSIDE the breaker so a
            // flaky-but-recovering SiliconFlow isn't mis-classified as down.
            java.util.function.Supplier<List<float[]>> upstream = () -> doCallEmbeddings(texts);
            java.util.function.Supplier<List<float[]>> guarded =
                    (circuitBreaker == null) ? upstream
                            : CircuitBreaker.decorateSupplier(circuitBreaker, upstream);
            try {
                return guarded.get();
            } catch (CallNotPermittedException ex) {
                // Breaker OPEN — don't even try SiliconFlow.
                throw new EmbeddingUnavailableException(
                        "SiliconFlow circuit breaker OPEN — skipping upstream call", ex);
            }
        } finally {
            sample.stop(Timer.builder("rag.embedding.duration.ms")
                    .tag("provider", "siliconflow")
                    .register(meterRegistry));
        }
    }

    /** Plain HTTP-with-retry call extracted so the breaker can wrap it cleanly. */
    private List<float[]> doCallEmbeddings(List<String> texts) {
        EmbeddingRequest body = new EmbeddingRequest();
        body.model = properties.getEmbedding().getModel();
        body.input = texts;

        int maxRetries = Math.max(0, properties.getEmbedding().getMaxRetries());
        int timeoutSeconds = Math.max(1, properties.getEmbedding().getTimeoutSeconds());

        try {
            EmbeddingResponse resp = webClient.post()
                    .uri("/embeddings")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(EmbeddingResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .retryWhen(Retry.backoff(maxRetries, backoffMin)
                            .maxBackoff(backoffMax)
                            .filter(SiliconFlowEmbeddingGateway::isTransient))
                    .onErrorResume(this::mapToEmbeddingUnavailable)
                    .block();

            if (resp == null || resp.data == null || resp.data.isEmpty()) {
                throw new EmbeddingUnavailableException("SiliconFlow returned empty embedding response");
            }
            if (resp.data.size() != texts.size()) {
                throw new EmbeddingUnavailableException(
                        "SiliconFlow returned " + resp.data.size() + " vectors for " + texts.size() + " inputs");
            }
            List<float[]> vectors = new ArrayList<>(resp.data.size());
            for (EmbeddingData d : resp.data) {
                if (d.embedding == null || d.embedding.length != dimension) {
                    throw new EmbeddingUnavailableException(
                            "SiliconFlow returned vector of length "
                                    + (d.embedding == null ? "null" : d.embedding.length)
                                    + " (expected " + dimension + ")");
                }
                vectors.add(d.embedding);
            }
            log.debug("SiliconFlowEmbeddingGateway fetched {} vectors dim={}", vectors.size(), dimension);
            return vectors;
        } catch (EmbeddingUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new EmbeddingUnavailableException("SiliconFlow embedding call failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Map WebClient / network errors to {@link EmbeddingUnavailableException}
     * so the caller gets a single, typed failure (no need to catch multiple).
     */
    private Mono<EmbeddingResponse> mapToEmbeddingUnavailable(Throwable ex) {
        if (ex instanceof EmbeddingUnavailableException eu) {
            return Mono.error(eu);
        }
        String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        log.warn("SiliconFlow embedding call failed (final attempt): {}", msg);
        return Mono.error(new EmbeddingUnavailableException("SiliconFlow embedding: " + msg, ex));
    }

    /**
     * 5xx / timeout / connection-refused → retryable.
     * 4xx (auth, bad request) → terminal.
     */
    private static boolean isTransient(Throwable ex) {
        if (ex instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode().is5xxServerError();
        }
        // TimeoutException, ConnectException, etc.
        return true;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JRE — this branch is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ─── DTOs (OpenAI-compatible) ────────────────────────────────────

    static class EmbeddingRequest {
        public String model;
        public List<String> input;
    }

    static class EmbeddingResponse {
        public List<EmbeddingData> data;
    }

    static class EmbeddingData {
        public int index;
        public float[] embedding;
    }
}