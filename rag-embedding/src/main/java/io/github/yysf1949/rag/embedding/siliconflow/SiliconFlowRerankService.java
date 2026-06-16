package io.github.yysf1949.rag.embedding.siliconflow;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.port.RerankResult;
import io.github.yysf1949.rag.core.port.RerankService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SiliconFlow {@code BAAI/bge-reranker-v2-m3} rerank adapter
 * (Phase 5-P4, spec §11.3, §13.8).
 *
 * <p>Talks to the OpenAI-style {@code POST /v1/rerank} endpoint. Input is
 * the rewritten query + the retrieval-pool candidate chunks; output is the
 * top-N by descending relevance score.</p>
 *
 * <h2>Error &amp; degradation contract</h2>
 * <ul>
 *   <li>Per {@link RerankService} contract: <b>never throws</b> on transient
 *       upstream errors — instead returns the first {@code topN} entries of
 *       the input order (the "no-op rerank" fallback, spec §7.5). The
 *       {@code QAService} logs the degradation via the upstream's exception
 *       trail (see {@code log.warn} below).</li>
 *   <li>Retries: up to {@code maxRetries} on 5xx / timeout. 4xx is terminal
 *       and falls straight through to the no-op rerank path.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2> Stateless after construction.
 */
public class SiliconFlowRerankService implements RerankService {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowRerankService.class);

    private final Duration backoffMin;
    private final Duration backoffMax;

    private final WebClient webClient;
    private final SiliconFlowProperties properties;

    public SiliconFlowRerankService(WebClient webClient, SiliconFlowProperties properties) {
        this(webClient, properties, Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    /** Test-friendly constructor — short backoff so hermetic tests don't sleep. */
    public SiliconFlowRerankService(WebClient webClient, SiliconFlowProperties properties,
                                     Duration backoffMin, Duration backoffMax) {
        this.webClient = webClient;
        this.properties = properties;
        this.backoffMin = backoffMin;
        this.backoffMax = backoffMax;
    }

    @Override
    public List<Chunk> rerank(String query, List<Chunk> candidates, int topN) {
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        // Build documents list — use Chunk.content (spec §4 model).
        List<String> documents = new ArrayList<>(candidates.size());
        for (Chunk c : candidates) {
            documents.add(c.content() == null ? "" : c.content());
        }

        RerankRequest body = new RerankRequest();
        body.model = properties.getRerank().getModel();
        body.query = query;
        body.documents = documents;
        body.top_n = Math.min(topN, candidates.size());
        body.return_documents = false;

        int maxRetries = Math.max(0, properties.getRerank().getMaxRetries());
        int timeoutSeconds = Math.max(1, properties.getRerank().getTimeoutSeconds());

        try {
            RerankResponse resp = webClient.post()
                    .uri("/rerank")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(RerankResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .retryWhen(Retry.backoff(maxRetries, backoffMin)
                            .maxBackoff(backoffMax)
                            .filter(SiliconFlowRerankService::isTransient))
                    .block();

            if (resp == null || resp.results == null || resp.results.isEmpty()) {
                log.warn("SiliconFlowRerankService: empty response — degrading to input order");
                return degrade(candidates, topN);
            }

            List<Chunk> out = new ArrayList<>(resp.results.size());
            for (ApiRerankResult r : resp.results) {
                if (r.index < 0 || r.index >= candidates.size()) {
                    log.warn("SiliconFlowRerankService: out-of-range index={} poolSize={} — skipping", r.index, candidates.size());
                    continue;
                }
                out.add(candidates.get(r.index));
            }
            if (out.isEmpty()) {
                return degrade(candidates, topN);
            }
            log.debug("SiliconFlowRerankService: reranked {} → {} (top_n={})",
                    candidates.size(), out.size(), body.top_n);
            return Collections.unmodifiableList(out);
        } catch (RuntimeException ex) {
            // Per contract: never throw. Degrade to input order so caller can continue.
            log.warn("SiliconFlowRerankService: call failed — degrading to input order: {}", ex.toString());
            return degrade(candidates, topN);
        }
    }

    private static List<Chunk> degrade(List<Chunk> candidates, int topN) {
        return candidates.subList(0, Math.min(topN, candidates.size()));
    }

    private static boolean isTransient(Throwable ex) {
        if (ex instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode().is5xxServerError();
        }
        return true;
    }

    @Override
    public List<RerankResult> rerankWithScores(String query, List<Chunk> candidates, int topN) {
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        List<String> documents = new ArrayList<>(candidates.size());
        for (Chunk c : candidates) {
            documents.add(c.content() == null ? "" : c.content());
        }

        RerankRequest body = new RerankRequest();
        body.model = properties.getRerank().getModel();
        body.query = query;
        body.documents = documents;
        body.top_n = Math.min(topN, candidates.size());
        body.return_documents = false;

        int maxRetries = Math.max(0, properties.getRerank().getMaxRetries());
        int timeoutSeconds = Math.max(1, properties.getRerank().getTimeoutSeconds());

        try {
            RerankResponse resp = webClient.post()
                    .uri("/rerank")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(RerankResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .retryWhen(Retry.backoff(maxRetries, backoffMin)
                            .maxBackoff(backoffMax)
                            .filter(SiliconFlowRerankService::isTransient))
                    .block();

            if (resp == null || resp.results == null || resp.results.isEmpty()) {
                log.warn("SiliconFlowRerankService.rerankWithScores: empty response — degrading");
                return degradeWithScores(candidates, topN);
            }

            List<io.github.yysf1949.rag.core.port.RerankResult> out = new ArrayList<>(resp.results.size());
            for (ApiRerankResult r : resp.results) {
                if (r.index < 0 || r.index >= candidates.size()) {
                    log.warn("SiliconFlowRerankService: out-of-range index={} poolSize={} — skipping", r.index, candidates.size());
                    continue;
                }
                double score = r.relevance_score != null ? r.relevance_score : 0.0;
                out.add(new io.github.yysf1949.rag.core.port.RerankResult(candidates.get(r.index), score));
            }
            if (out.isEmpty()) {
                return degradeWithScores(candidates, topN);
            }
            log.debug("SiliconFlowRerankService.rerankWithScores: {} → {} (top_n={})",
                    candidates.size(), out.size(), body.top_n);
            return Collections.unmodifiableList(out);
        } catch (RuntimeException ex) {
            log.warn("SiliconFlowRerankService.rerankWithScores: call failed — degrading: {}", ex.toString());
            return degradeWithScores(candidates, topN);
        }
    }

    private static List<io.github.yysf1949.rag.core.port.RerankResult> degradeWithScores(List<Chunk> candidates, int topN) {
        return candidates.subList(0, Math.min(topN, candidates.size())).stream()
                .map(c -> new io.github.yysf1949.rag.core.port.RerankResult(c, 0.0))
                .toList();
    }

    // ─── DTOs (SiliconFlow /v1/rerank schema) ────────────────────────

    static class RerankRequest {
        public String model;
        public String query;
        public List<String> documents;
        public int top_n;
        public boolean return_documents;
    }

    static class RerankResponse {
        public String id;
        public List<ApiRerankResult> results;
    }

    static class ApiRerankResult {
        public int index;
        public Double relevance_score;
    }
}