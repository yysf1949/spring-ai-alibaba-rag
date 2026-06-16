package io.github.yysf1949.rag.eval;

/**
 * Result of evaluating a single eval fixture against the RAG system.
 *
 * @param name            fixture name (derived from filename)
 * @param recallAtK        fraction of expected chunks found in retrieved pool
 * @param citationCoverage fraction of required source URIs present in citations
 * @param groundRate       fraction of required substrings present in final answer
 * @param pass             true when all metrics meet the pass threshold
 */
public record EvalResult(
        String name,
        double recallAtK,
        double citationCoverage,
        double groundRate,
        boolean pass
) {}