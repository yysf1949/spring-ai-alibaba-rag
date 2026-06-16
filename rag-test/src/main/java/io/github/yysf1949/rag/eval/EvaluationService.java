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
 * Lightweight eval engine — spec §9.3 + §11.3.
 * Computes Recall@K, MRR, and Grounded Rate from an Answer + expected assertions.
 */
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    /**
     * @param answer            the QAService answer
     * @param expectedChunkIds  chunk IDs that should appear in the reranked set
     * @param expectedSourceUris source URIs that should appear in citations
     * @param fixtureName       name for the result report
     * @return EvalResult
     */
    public EvalResult evaluate(Answer answer,
                                List<String> expectedChunkIds,
                                List<String> expectedSourceUris,
                                String fixtureName) {
        // Recall@K: how many of the expected chunks appear in the reranked set?
        List<Chunk> reranked = answer.reranked();
        List<String> rerankedIds = reranked.stream()
                .map(Chunk::chunkId)
                .toList();

        long recallHits = expectedChunkIds.stream()
                .filter(rerankedIds::contains)
                .count();
        double recallAtK = expectedChunkIds.isEmpty() ? 1.0 :
                (double) recallHits / expectedChunkIds.size();

        // MRR: first relevant chunk's reciprocal rank
        double mrr = 0.0;
        for (int i = 0; i < rerankedIds.size(); i++) {
            if (expectedChunkIds.contains(rerankedIds.get(i))) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }

        // Grounded Rate: expected source URIs present in citations?
        Set<String> citationSources = answer.citations().stream()
                .map(Citation::sourceUri)
                .collect(Collectors.toSet());
        long groundedHits = expectedSourceUris.stream()
                .filter(citationSources::contains)
                .count();
        double groundedRate = expectedSourceUris.isEmpty() ? 1.0 :
                (double) groundedHits / expectedSourceUris.size();

        boolean pass = recallAtK >= 0.5 && groundedRate >= 0.5;

        EvalResult result = new EvalResult(fixtureName, recallAtK, mrr, groundedRate, pass);
        log.info("Eval result [{}]: recall@K={}, MRR={}, groundedRate={}, pass={}",
                fixtureName, String.format("%.3f", recallAtK), String.format("%.3f", mrr),
                String.format("%.3f", groundedRate), pass);
        return result;
    }
}
