package io.github.yysf1949.rag.eval;

import java.util.List;

/**
 * Rerank 模型评测结果 — 单个模型的聚合指标.
 *
 * @param modelName     模型名
 * @param fixtureCount  评测数据条数
 * @param avgNdcg       平均 NDCG@5
 * @param avgMrr        平均 MRR
 * @param avgRecall     平均 Recall@5
 * @param avgPrecision  平均 Precision@5
 * @param perQueryNdcg  每条 query 的 NDCG@5
 * @param perQueryMrr   每条 query 的 MRR
 * @param perQueryRecall 每条 query 的 Recall@5
 * @param perQueryPrecision 每条 query 的 Precision@5
 */
public record RerankEvalResult(
        String modelName,
        int fixtureCount,
        double avgNdcg,
        double avgMrr,
        double avgRecall,
        double avgPrecision,
        List<Double> perQueryNdcg,
        List<Double> perQueryMrr,
        List<Double> perQueryRecall,
        List<Double> perQueryPrecision
) {}
