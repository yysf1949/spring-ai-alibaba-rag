package io.github.yysf1949.rag.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rerank 模型 A/B 评测器 — Phase 41 / R18.
 *
 * <p>用 Mock 数据集 + 模拟 rerank 打分对 BGE / BCEmbedding / Cohere 三种模型
 * 进行离线评测。不调用真实模型 API，用预定义的相关性分数矩阵模拟各模型的
 * 排序差异。</p>
 *
 * <h2>评测流程</h2>
 * <ol>
 *   <li>加载 Mock 数据集 (10 queries × 10 candidates, 每条有 ground truth relevance 0-4)</li>
 *   <li>对每个模型: 用模拟分数 rerank candidates, 取 top-5</li>
 *   <li>计算 NDCG@5, MRR, Recall@5, Precision@5</li>
 *   <li>输出对比报告</li>
 * </ol>
 *
 * <h2>Mock 模型差异模拟</h2>
 * <ul>
 *   <li><b>BGE (bge-reranker-v2-m3)</b> — 中英双语, 对语义匹配强, 对关键词匹配弱</li>
 *   <li><b>BCEmbedding</b> — 中英双语, 对关键词匹配强, 对语义匹配中等</li>
 *   <li><b>Cohere (rerank-english-v3.0)</b> — 英文专精, 语义匹配最强, 中文弱</li>
 * </ul>
 */
public class RerankABEvaluator {

    /** NDCG 位置截断 */
    private static final int TOP_K = 5;

    /** Mock 数据集: query → candidate content → ground truth relevance (0-4 scale) */
    private final List<RerankEvalFixture> fixtures;

    public RerankABEvaluator() {
        this(RerankEvalFixtures.defaultFixtures());
    }

    public RerankABEvaluator(List<RerankEvalFixture> fixtures) {
        this.fixtures = fixtures;
    }

    /**
     * 运行全部三个模型的 A/B 评测.
     *
     * @return 模型名 → 评测结果
     */
    public Map<String, RerankEvalResult> runAll() {
        Map<String, MockRerankModel> models = new java.util.LinkedHashMap<>();
        models.put("BGE (bge-reranker-v2-m3)", new MockBgeRerank());
        models.put("BCEmbedding", new MockBCEmbeddingRerank());
        models.put("Cohere (rerank-english-v3.0)", new MockCohereRerank());

        Map<String, RerankEvalResult> results = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, MockRerankModel> entry : models.entrySet()) {
            results.put(entry.getKey(), evaluateModel(entry.getKey(), entry.getValue()));
        }
        return results;
    }

    /**
     * 评测单个模型.
     */
    public RerankEvalResult evaluateModel(String modelName, MockRerankModel rerankModel) {
        List<Double> ndcgs = new ArrayList<>();
        List<Double> mrrs = new ArrayList<>();
        List<Double> recalls = new ArrayList<>();
        List<Double> precisions = new ArrayList<>();

        for (RerankEvalFixture fixture : fixtures) {
            // Rerank using model-specific scoring with ground truth
            List<String> rankedIds = rerankModel.rerank(fixture);

            // NDCG@K
            ndcgs.add(computeNdcg(fixture, rankedIds, TOP_K));
            // MRR (first relevant chunk, relevance >= 3)
            mrrs.add(computeMrr(fixture, rankedIds));
            // Recall@K
            recalls.add(computeRecall(fixture, rankedIds, TOP_K));
            // Precision@K
            precisions.add(computePrecision(fixture, rankedIds, TOP_K));
        }

        double avgNdcg = ndcgs.stream().mapToDouble(d -> d).average().orElse(0.0);
        double avgMrr = mrrs.stream().mapToDouble(d -> d).average().orElse(0.0);
        double avgRecall = recalls.stream().mapToDouble(d -> d).average().orElse(0.0);
        double avgPrecision = precisions.stream().mapToDouble(d -> d).average().orElse(0.0);

        return new RerankEvalResult(
                modelName,
                fixtures.size(),
                avgNdcg, avgMrr, avgRecall, avgPrecision,
                ndcgs, mrrs, recalls, precisions
        );
    }

    /**
     * 生成 Markdown 格式的对比报告.
     */
    public String generateReport(Map<String, RerankEvalResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Rerank 模型 A/B 评测报告 (Phase 41 / R18)\n\n");
        sb.append("**数据集**: Mock (").append(fixtures.size()).append(" queries × 10 candidates)\n");
        sb.append("**指标**: NDCG@5, MRR, Recall@5, Precision@5\n");
        sb.append("**日期**: ").append(java.time.LocalDate.now()).append("\n\n");

        sb.append("## 总览\n\n");
        sb.append("```\n");
        sb.append(String.format("%-28s %8s %8s %8s %8s%n",
                "Model", "NDCG@5", "MRR", "Recall@5", "Prec@5"));
        sb.append("-".repeat(64)).append("\n");
        for (RerankEvalResult r : results.values()) {
            sb.append(String.format("%-28s %8.4f %8.4f %8.4f %8.4f%n",
                    r.modelName(), r.avgNdcg(), r.avgMrr(), r.avgRecall(), r.avgPrecision()));
        }
        sb.append("```\n\n");

        sb.append("## 详细结果\n\n");
        for (RerankEvalResult r : results.values()) {
            sb.append("### ").append(r.modelName()).append("\n\n");
            sb.append("- Queries: ").append(r.fixtureCount()).append("\n");
            sb.append("- NDCG@5:  ").append(String.format("%.4f", r.avgNdcg())).append("\n");
            sb.append("- MRR:     ").append(String.format("%.4f", r.avgMrr())).append("\n");
            sb.append("- Recall@5:").append(String.format("%.4f", r.avgRecall())).append("\n");
            sb.append("- Prec@5:  ").append(String.format("%.4f", r.avgPrecision())).append("\n\n");

            sb.append("Per-query NDCG@5:\n```\n");
            for (int i = 0; i < r.perQueryNdcg().size(); i++) {
                sb.append(String.format("  Q%-2d: %.4f%n", i + 1, r.perQueryNdcg().get(i)));
            }
            sb.append("```\n\n");
        }

        // Recommendation
        RerankEvalResult best = results.values().stream()
                .max(java.util.Comparator.comparingDouble(RerankEvalResult::avgNdcg))
                .orElse(null);
        if (best != null) {
            sb.append("## 推荐\n\n");
            sb.append("基于 NDCG@5 最优: **").append(best.modelName())
                    .append("** (NDCG@5=").append(String.format("%.4f", best.avgNdcg()))
                    .append(")\n\n");
        }

        sb.append("## 说明\n\n");
        sb.append("- 本报告使用 Mock 数据集 + 模拟打分, 未调用真实模型 API\n");
        sb.append("- BGE: 模拟 bge-reranker-v2-m3 (中英双语, 语义匹配强)\n");
        sb.append("- BCEmbedding: 模拟 BCEmbedding (中英双语, 关键词匹配强)\n");
        sb.append("- Cohere: 模拟 rerank-english-v3.0 (英文专精, 中文弱)\n");
        sb.append("- 实际选型需在真实数据集上验证\n");

        return sb.toString();
    }

    // --- Metrics ---

    private double computeNdcg(RerankEvalFixture fixture, List<String> rankedIds, int k) {
        // DCG: sum of (relevance / log2(rank+1)) for top-k
        double dcg = 0.0;
        for (int i = 0; i < Math.min(k, rankedIds.size()); i++) {
            int rel = fixture.relevance(rankedIds.get(i));
            dcg += rel / (Math.log(i + 2) / Math.log(2)); // rank starts at 1 → log2(2)=1
        }

        // IDCG: ideal DCG (sorted by relevance descending)
        List<Integer> idealRels = new ArrayList<>();
        for (String id : fixture.candidateIds()) {
            idealRels.add(fixture.relevance(id));
        }
        idealRels.sort(java.util.Collections.reverseOrder());
        double idcg = 0.0;
        for (int i = 0; i < Math.min(k, idealRels.size()); i++) {
            idcg += idealRels.get(i) / (Math.log(i + 2) / Math.log(2));
        }

        return idcg == 0 ? 0.0 : dcg / idcg;
    }

    private double computeMrr(RerankEvalFixture fixture, List<String> rankedIds) {
        for (int i = 0; i < rankedIds.size(); i++) {
            if (fixture.relevance(rankedIds.get(i)) >= 3) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private double computeRecall(RerankEvalFixture fixture, List<String> rankedIds, int k) {
        int relevantTotal = 0;
        for (String id : fixture.candidateIds()) {
            if (fixture.relevance(id) >= 3) relevantTotal++;
        }
        if (relevantTotal == 0) return 0.0;

        int relevantInTopK = 0;
        for (int i = 0; i < Math.min(k, rankedIds.size()); i++) {
            if (fixture.relevance(rankedIds.get(i)) >= 3) relevantInTopK++;
        }
        return (double) relevantInTopK / relevantTotal;
    }

    private double computePrecision(RerankEvalFixture fixture, List<String> rankedIds, int k) {
        int relevantInTopK = 0;
        for (int i = 0; i < Math.min(k, rankedIds.size()); i++) {
            if (fixture.relevance(rankedIds.get(i)) >= 3) relevantInTopK++;
        }
        return (double) relevantInTopK / Math.min(k, rankedIds.size());
    }

    // --- Mock Rerank Models ---

    /**
     * Mock rerank model interface — takes a fixture (with ground truth) and returns ranked chunk IDs.
     */
    public interface MockRerankModel {
        List<String> rerank(RerankEvalFixture fixture);
    }

    /**
     * Mock BGE reranker — 模拟 bge-reranker-v2-m3.
     * 中英双语均衡, 直接用 relevance 作基础分 + 同语言加成.
     */
    static class MockBgeRerank implements MockRerankModel {
        @Override
        public List<String> rerank(RerankEvalFixture fixture) {
            List<Map.Entry<String, Double>> scored = new ArrayList<>();
            for (var chunk : fixture.candidates()) {
                int rel = fixture.relevance(chunk.chunkId());
                double score = RerankEvalFixtures.simulatedScore("BGE", fixture.query(), chunk.content(), rel);
                scored.add(Map.entry(chunk.chunkId(), score));
            }
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            return scored.stream().map(Map.Entry::getKey).toList();
        }
    }

    /**
     * Mock BCEmbedding reranker — 关键词偏重.
     */
    static class MockBCEmbeddingRerank implements MockRerankModel {
        @Override
        public List<String> rerank(RerankEvalFixture fixture) {
            List<Map.Entry<String, Double>> scored = new ArrayList<>();
            for (var chunk : fixture.candidates()) {
                int rel = fixture.relevance(chunk.chunkId());
                double score = RerankEvalFixtures.simulatedScore("BCE", fixture.query(), chunk.content(), rel);
                scored.add(Map.entry(chunk.chunkId(), score));
            }
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            return scored.stream().map(Map.Entry::getKey).toList();
        }
    }

    /**
     * Mock Cohere reranker — 英文专精, 中文弱.
     */
    static class MockCohereRerank implements MockRerankModel {
        @Override
        public List<String> rerank(RerankEvalFixture fixture) {
            List<Map.Entry<String, Double>> scored = new ArrayList<>();
            for (var chunk : fixture.candidates()) {
                int rel = fixture.relevance(chunk.chunkId());
                double score = RerankEvalFixtures.simulatedScore("Cohere", fixture.query(), chunk.content(), rel);
                scored.add(Map.entry(chunk.chunkId(), score));
            }
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            return scored.stream().map(Map.Entry::getKey).toList();
        }
    }
}
