package io.github.yysf1949.rag.eval;

import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.Citation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lightweight eval metric calculator for Q&A end-to-end tests.
 *
 * <p>Computes three metrics for a single fixture:</p>
 * <ol>
 *   <li><b>recall@K</b> — fraction of {@code expectedChunkIds} found in
 *       {@code Answer.retrieved()} (the full TopK pool before reranking).</li>
 *   <li><b>citationCoverage</b> — fraction of {@code requiredSourceUris}
 *       present in the answer's {@link Citation} list.</li>
 *   <li><b>groundRate</b> — 1.0 if the answer's {@code finalText} contains
 *       <em>every</em> required substring, else 0.0.</li>
 * </ol>
 *
 * <p>A fixture <em>passes</em> when all three metrics are ≥50%
 * (recall ≥0.5, citationCoverage ≥0.5, groundRate ≥0.5).</p>
 */
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    /** Minimum threshold for each metric to count as a pass. */
    static final double PASS_THRESHOLD = 0.5;

    /**
     * Evaluate a single answer against expected criteria.
     *
     * @param answer              the RAG system's answer
     * @param expectedChunkIds    chunk ids that must appear in the retrieved pool
     * @param requiredSourceUris  source URIs that must appear in citations
     * @param requiredSubstrings  substrings that must appear in finalText
     * @param fixtureName         human-readable label for logging
     * @return {@link EvalResult} with all three metrics and the pass/fail verdict
     */
    public EvalResult evaluate(
            Answer answer,
            List<String> expectedChunkIds,
            List<String> requiredSourceUris,
            List<String> requiredSubstrings,
            String fixtureName
    ) {
        // ── recall@K ────────────────────────────────────────────────
        Set<String> retrievedIds = answer.retrieved().stream()
                .map(Chunk::chunkId)
                .collect(Collectors.toSet());

        double recallAtK;
        if (expectedChunkIds == null || expectedChunkIds.isEmpty()) {
            recallAtK = 1.0;
        } else {
            long found = expectedChunkIds.stream()
                    .filter(retrievedIds::contains)
                    .count();
            recallAtK = (double) found / expectedChunkIds.size();
        }

        // ── citationCoverage ────────────────────────────────────────
        Set<String> citationUris = answer.citations().stream()
                .map(c -> c.sourceUri() != null ? c.sourceUri() : "")
                .collect(Collectors.toSet());

        double citationCoverage;
        if (requiredSourceUris == null || requiredSourceUris.isEmpty()) {
            citationCoverage = 1.0;
        } else {
            long matched = requiredSourceUris.stream()
                    .filter(u -> u != null && citationUris.contains(u))
                    .count();
            citationCoverage = (double) matched / requiredSourceUris.size();
        }

        // ── groundRate ──────────────────────────────────────────────
        String finalText = answer.finalText() != null ? answer.finalText() : "";

        double groundRate;
        if (requiredSubstrings == null || requiredSubstrings.isEmpty()) {
            groundRate = 1.0;
        } else {
            long matched = requiredSubstrings.stream()
                    .filter(s -> s != null && finalText.contains(s))
                    .count();
            groundRate = (double) matched / requiredSubstrings.size();
        }

        // ── pass decision ───────────────────────────────────────────
        boolean pass = recallAtK >= PASS_THRESHOLD
                && citationCoverage >= PASS_THRESHOLD
                && groundRate >= PASS_THRESHOLD;

        log.info("Eval '{}': recall@K={:.2f}, citationCoverage={:.2f}, groundRate={:.2f} → {}",
                fixtureName, recallAtK, citationCoverage, groundRate,
                pass ? "PASS" : "FAIL");

        return new EvalResult(fixtureName, recallAtK, citationCoverage, groundRate, pass);
    }
}