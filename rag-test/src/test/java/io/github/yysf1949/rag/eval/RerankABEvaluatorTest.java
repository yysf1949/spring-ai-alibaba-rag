package io.github.yysf1949.rag.eval;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RerankABEvaluator} 测试.
 *
 * <p>覆盖: 三模型评测 + 指标计算 + 报告生成.</p>
 */
class RerankABEvaluatorTest {

    @Test
    void runAllReturnsThreeModels() {
        RerankABEvaluator evaluator = new RerankABEvaluator();
        Map<String, RerankEvalResult> results = evaluator.runAll();

        assertEquals(3, results.size());
        assertTrue(results.containsKey("BGE (bge-reranker-v2-m3)"));
        assertTrue(results.containsKey("BCEmbedding"));
        assertTrue(results.containsKey("Cohere (rerank-english-v3.0)"));
    }

    @Test
    void allModelsProduceValidMetrics() {
        RerankABEvaluator evaluator = new RerankABEvaluator();
        Map<String, RerankEvalResult> results = evaluator.runAll();

        for (RerankEvalResult r : results.values()) {
            assertEquals(10, r.fixtureCount());
            assertTrue(r.avgNdcg() >= 0.0 && r.avgNdcg() <= 1.0,
                    r.modelName() + " NDCG out of range: " + r.avgNdcg());
            assertTrue(r.avgMrr() >= 0.0 && r.avgMrr() <= 1.0,
                    r.modelName() + " MRR out of range: " + r.avgMrr());
            assertTrue(r.avgRecall() >= 0.0 && r.avgRecall() <= 1.0,
                    r.modelName() + " Recall out of range: " + r.avgRecall());
            assertTrue(r.avgPrecision() >= 0.0 && r.avgPrecision() <= 1.0,
                    r.modelName() + " Precision out of range: " + r.avgPrecision());
            assertEquals(10, r.perQueryNdcg().size());
            assertEquals(10, r.perQueryMrr().size());
        }
    }

    @Test
    void bgeOutperformsCohereOnChineseQueries() {
        // BGE should do better on Chinese queries than Cohere (which is English-focused)
        RerankABEvaluator evaluator = new RerankABEvaluator();
        Map<String, RerankEvalResult> results = evaluator.runAll();

        RerankEvalResult bge = results.get("BGE (bge-reranker-v2-m3)");
        RerankEvalResult cohere = results.get("Cohere (rerank-english-v3.0)");

        // Chinese queries are Q1 (index 0), Q3 (2), Q5 (4), Q7 (6), Q9 (8)
        double bgeChineseNdcg = avg(bge.perQueryNdcg(), 0, 2, 4, 6, 8);
        double cohereChineseNdcg = avg(cohere.perQueryNdcg(), 0, 2, 4, 6, 8);

        assertTrue(bgeChineseNdcg > cohereChineseNdcg,
                "BGE should outperform Cohere on Chinese queries: BGE=" + bgeChineseNdcg
                        + " vs Cohere=" + cohereChineseNdcg);
    }

    @Test
    void cohereOutperformsOnEnglishQueries() {
        // Cohere should do better on English queries
        RerankABEvaluator evaluator = new RerankABEvaluator();
        Map<String, RerankEvalResult> results = evaluator.runAll();

        RerankEvalResult bge = results.get("BGE (bge-reranker-v2-m3)");
        RerankEvalResult cohere = results.get("Cohere (rerank-english-v3.0)");

        // English queries are Q2 (1), Q4 (3), Q6 (5), Q8 (7), Q10 (9)
        double bgeEnglishNdcg = avg(bge.perQueryNdcg(), 1, 3, 5, 7, 9);
        double cohereEnglishNdcg = avg(cohere.perQueryNdcg(), 1, 3, 5, 7, 9);

        assertTrue(cohereEnglishNdcg > bgeEnglishNdcg,
                "Cohere should outperform BGE on English queries: Cohere=" + cohereEnglishNdcg
                        + " vs BGE=" + bgeEnglishNdcg);
    }

    @Test
    void generateReportContainsAllModels() {
        RerankABEvaluator evaluator = new RerankABEvaluator();
        Map<String, RerankEvalResult> results = evaluator.runAll();
        String report = evaluator.generateReport(results);

        assertNotNull(report);
        assertTrue(report.contains("BGE"));
        assertTrue(report.contains("BCEmbedding"));
        assertTrue(report.contains("Cohere"));
        assertTrue(report.contains("NDCG@5"));
        assertTrue(report.contains("MRR"));
        assertTrue(report.contains("Recall@5"));
        assertTrue(report.contains("推荐"));
    }

    @Test
    void reportContainsRecommendation() {
        RerankABEvaluator evaluator = new RerankABEvaluator();
        Map<String, RerankEvalResult> results = evaluator.runAll();
        String report = evaluator.generateReport(results);

        assertTrue(report.contains("## 推荐"));
        assertTrue(report.contains("基于 NDCG@5 最优"));
    }

    @Test
    void perQueryMetricsHaveCorrectSize() {
        RerankABEvaluator evaluator = new RerankABEvaluator();
        Map<String, RerankEvalResult> results = evaluator.runAll();

        for (RerankEvalResult r : results.values()) {
            assertEquals(10, r.perQueryNdcg().size());
            assertEquals(10, r.perQueryMrr().size());
            assertEquals(10, r.perQueryRecall().size());
            assertEquals(10, r.perQueryPrecision().size());
        }
    }

    @Test
    void ndcgIsNonNegativeAndBoundedByOne() {
        RerankABEvaluator evaluator = new RerankABEvaluator();
        Map<String, RerankEvalResult> results = evaluator.runAll();

        for (RerankEvalResult r : results.values()) {
            for (double ndcg : r.perQueryNdcg()) {
                assertTrue(ndcg >= 0.0, "NDCG should be >= 0: " + ndcg);
                assertTrue(ndcg <= 1.0, "NDCG should be <= 1: " + ndcg);
            }
        }
    }

    private double avg(java.util.List<Double> values, int... indices) {
        double sum = 0;
        for (int idx : indices) {
            sum += values.get(idx);
        }
        return sum / indices.length;
    }
}
