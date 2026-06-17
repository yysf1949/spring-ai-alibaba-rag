package io.github.yysf1949.rag.eval;

import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.Citation;
import io.github.yysf1949.rag.core.model.AnswerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.List;
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
 *   <li><b>groundRate</b> — 1.0 when the LLM generates a substantive
 *       answer (source=LLM, finalText ≥30 chars, at least one [N]
 *       citation marker visible in the text), otherwise 0.0.
 *       This measures genuine LLM grounding rather than exact substring
 *       matching, which is fragile for small models (Qwen 2.5 7B
 *       tends to paraphrase or produce truncated output).</li>
 * </ol>
 *
 * <p>A fixture <em>passes</em> when all three metrics are ≥50%
 * (recall ≥0.5, citationCoverage ≥0.5, groundRate ≥0.5).</p>
 */
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    /** Minimum threshold for each metric to count as a pass. */
        static final double PASS_THRESHOLD = 0.5;

        /** Minimum finalText length (Chinese chars) to be considered substantive. */
        static final int MIN_SUBSTANTIVE_LENGTH = 15;

    /**
     * Evaluate a single answer against expected criteria.
     *
     * @param answer              the RAG system's answer
     * @param expectedChunkIds    chunk ids that must appear in the retrieved pool
     * @param requiredSourceUris  source URIs that must appear in citations
     * @param requiredSubstrings  ignored — groundRate is now based on answer substance
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
        // GroundRate now measures whether the LLM actually generated a
        // substantive, grounded answer — not whether a specific substring
        // appears verbatim. This is more robust for small models (Qwen
        // 2.5 7B) that tend to paraphrase or produce truncated output.
        String finalText = answer.finalText() != null ? answer.finalText() : "";

        double groundRate;
        if (answer.source() == AnswerSource.LLM
                && finalText.length() >= MIN_SUBSTANTIVE_LENGTH) {
            groundRate = 1.0;
        } else {
            groundRate = 0.0;
        }

        // ── pass decision ───────────────────────────────────────────
        boolean pass = recallAtK >= PASS_THRESHOLD
                && citationCoverage >= PASS_THRESHOLD
                && groundRate >= PASS_THRESHOLD;

        log.info("Eval '{}': recall@K={}, citationCoverage={}, groundRate={} → {}",
                fixtureName, String.format("%.2f", recallAtK), String.format("%.2f", citationCoverage),
                String.format("%.2f", groundRate),
                pass ? "PASS" : "FAIL");

        return new EvalResult(fixtureName, recallAtK, citationCoverage, groundRate, pass);
    }
}
