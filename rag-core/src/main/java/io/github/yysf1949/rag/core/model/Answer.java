package io.github.yysf1949.rag.core.model;

import java.util.List;
import java.util.Map;

/**
 * Final QA result. Design spec §4 + §13.11.
 *
 * <p>Carries both the answer text <em>and</em> the full retrieval trail
 * (retrieved → reranked → citations), so downstream consumers can do
 * grounded rate / citation coverage metrics without re-querying.</p>
 *
 * @param tenantId         for cache key routing
 * @param queryHash        sha-256 of the normalized query (cache key)
 * @param rewrittenQuery   post-rewrite text (may equal {@code rawText} when rewrite was a no-op)
 * @param retrieved        candidate chunks from vector search (TopK)
 * @param reranked         after-rerank order (TopN, usually 5)
 * @param finalText        answer text shown to the user
 * @param citations        UI-facing provenance pointers
 * @param source           which leg produced the answer (cache / llm / fallback)
 * @param latencyMs        end-to-end wall clock
 * @param metrics          per-stage timings — see {@code rag.qa.latency.ms{stage}}
 */
public record Answer(
        String tenantId,
        String queryHash,
        String rewrittenQuery,
        List<Chunk> retrieved,
        List<Chunk> reranked,
        String finalText,
        List<Citation> citations,
        AnswerSource source,
        long latencyMs,
        Map<String, Object> metrics
) {

    public Answer {
        retrieved = retrieved == null ? List.of() : List.copyOf(retrieved);
        reranked = reranked == null ? List.of() : List.copyOf(reranked);
        citations = citations == null ? List.of() : List.copyOf(citations);
        if (metrics == null) {
            metrics = Map.of();
        }
        if (source == null) {
            source = AnswerSource.LLM;
        }
    }

    public int retrievedCount() {
        return retrieved.size();
    }

    public int rerankedCount() {
        return reranked.size();
    }
}