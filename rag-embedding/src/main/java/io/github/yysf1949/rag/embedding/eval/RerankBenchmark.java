package io.github.yysf1949.rag.embedding.eval;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.port.RerankResult;
import io.github.yysf1949.rag.core.port.RerankService;

import java.util.*;

/**
 * Rerank A/B 评测器 — Phase 41 T2 (R18).
 *
 * <p>用 Mock 数据集对多个 RerankService 实现打榜, 输出选型报告.</p>
 *
 * <h2>评测指标</h2>
 * <ul>
 *   <li><b>nDCG@5</b> — 归一化折损累积增益 (主指标, 越高越好)</li>
 *   <li><b>Precision@1</b> — top-1 命中率</li>
 *   <li><b>MRR</b> — 平均倒数排名</li>
 * </ul>
 */
public class RerankBenchmark {

    /** 评测数据条目: query + 候选 chunks + 人工标注的相关性 (0=不相关, 1=相关, 2=高度相关) */
    public record EvalItem(String query, List<Chunk> candidates, Map<String, Integer> relevanceLabels) {}

    /** 单个模型评测结果 */
    public record ModelResult(String modelName, double ndcg5, double precision1, double mrr, int itemsEvaluated) {}

    /** 生成选型报告 */
    public String runBenchmark(List<EvalItem> dataset, Map<String, RerankService> models) {
        List<ModelResult> results = new ArrayList<>();

        for (var entry : models.entrySet()) {
            ModelResult result = evaluateModel(entry.getKey(), entry.getValue(), dataset);
            results.add(result);
        }

        // 按 nDCG@5 降序排
        results.sort(Comparator.comparingDouble(ModelResult::ndcg5).reversed());

        return generateReport(results);
    }

    private ModelResult evaluateModel(String name, RerankService service, List<EvalItem> dataset) {
        double totalNdcg = 0;
        int totalP1 = 0;
        double totalMrr = 0;

        for (EvalItem item : dataset) {
            List<RerankResult> ranked = service.rerankWithScores(
                    item.query(), item.candidates(), 5);

            // nDCG@5
            totalNdcg += computeNdcg(ranked, item.relevanceLabels(), 5);

            // Precision@1
            if (!ranked.isEmpty()) {
                String topId = ranked.get(0).chunk().chunkId();
                Integer rel = item.relevanceLabels().get(topId);
                if (rel != null && rel > 0) totalP1++;
            }

            // MRR
            for (int i = 0; i < ranked.size(); i++) {
                String chunkId = ranked.get(i).chunk().chunkId();
                Integer rel = item.relevanceLabels().get(chunkId);
                if (rel != null && rel > 0) {
                    totalMrr += 1.0 / (i + 1);
                    break;
                }
            }
        }

        int n = dataset.size();
        return new ModelResult(
                name,
                totalNdcg / n,
                (double) totalP1 / n,
                totalMrr / n,
                n
        );
    }

    /** nDCG@k 计算 */
    private double computeNdcg(List<RerankResult> ranked, Map<String, Integer> labels, int k) {
        // DCG
        double dcg = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            String chunkId = ranked.get(i).chunk().chunkId();
            Integer rel = labels.getOrDefault(chunkId, 0);
            dcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }

        // IDCG (理想排序)
        List<Integer> idealRels = new ArrayList<>(labels.values());
        idealRels.sort(Comparator.reverseOrder());
        double idcg = 0;
        for (int i = 0; i < Math.min(k, idealRels.size()); i++) {
            idcg += (Math.pow(2, idealRels.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
        }

        return idcg == 0 ? 0 : dcg / idcg;
    }

    private String generateReport(List<ModelResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Rerank 模型选型报告 (Phase 41 T2)\n\n");
        sb.append("## 评测结果 (按 nDCG@5 降序)\n\n");
        sb.append("| 排名 | 模型 | nDCG@5 | Precision@1 | MRR | 评测条目 |\n");
        sb.append("|------|------|--------|-------------|-----|----------|\n");
        for (int i = 0; i < results.size(); i++) {
            ModelResult r = results.get(i);
            sb.append(String.format("| %d | %s | %.4f | %.4f | %.4f | %d |\n",
                    i + 1, r.modelName(), r.ndcg5(), r.precision1(), r.mrr(), r.itemsEvaluated()));
        }

        sb.append("\n## 推荐选型\n\n");
        if (!results.isEmpty()) {
            ModelResult best = results.get(0);
            sb.append(String.format("**推荐: %s** — nDCG@5=%.4f, Precision@1=%.4f, MRR=%.4f\n\n",
                    best.modelName(), best.ndcg5(), best.precision1(), best.mrr()));
            sb.append("### 选型依据\n");
            sb.append("- nDCG@5 是主指标, 衡量 top-5 排序质量\n");
            sb.append("- Precision@1 衡量 top-1 命中率 (用户最常看第一条)\n");
            sb.append("- MRR 衡量相关文档的平均排名倒数\n\n");
            sb.append("### 注意事项\n");
            sb.append("- 本评测使用 Mock 评分函数, 生产环境需用真实模型 API 复测\n");
            sb.append("- 中文场景 BCEmbedding 可能有优势, 英文场景 BGE/SiliconFlow 更稳定\n");
            sb.append("- 建议生产环境接入 SiliconFlow (已实现) 作为默认, BGE/BCEmbedding 作为备选\n");
        }

        return sb.toString();
    }
}
