package io.github.yysf1949.rag.embedding.bge;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.port.RerankResult;
import io.github.yysf1949.rag.core.port.RerankService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * BGE Reranker (BAAI/bge-reranker-v2-m3) 模拟适配器 — Phase 41 T2 (R18).
 *
 * <p><b>Mock 模式</b>: 不调用真实模型 API, 用确定性评分函数模拟 BGE 的排序行为.
 * 评分函数: 基于关键词重叠率 + 语义位置加权 + 确定性噪声.
 * 评分区间 [0, 1], 与真实 BGE 模型输出区间对齐.</p>
 *
 * <h2>选型评测用</h2>
 * <p>此适配器用于 Rerank A/B 选型评测, 不消耗真实 API 配额.
 * 评分公式设计为接近 BGE 模型的行为特征 (高精确率, 中等召回率).</p>
 */
public class BgeRerankService implements RerankService {

    private static final Logger log = LoggerFactory.getLogger(BgeRerankService.class);

    @Override
    public List<Chunk> rerank(String query, List<Chunk> candidates, int topN) {
        if (candidates.isEmpty()) return List.of();
        return rerankWithScores(query, candidates, topN).stream()
                .map(RerankResult::chunk)
                .toList();
    }

    @Override
    public List<RerankResult> rerankWithScores(String query, List<Chunk> candidates, int topN) {
        if (candidates.isEmpty()) return List.of();

        List<RerankResult> scored = new ArrayList<>();
        Random rng = new Random(query.hashCode()); // 确定性: 同 query 同结果

        for (int i = 0; i < candidates.size(); i++) {
            Chunk chunk = candidates.get(i);
            double score = computeBgeScore(query, chunk, i, rng);
            scored.add(new RerankResult(chunk, score));
        }

        scored.sort(Comparator.comparingDouble(RerankResult::relevanceScore).reversed());
        return scored.subList(0, Math.min(topN, scored.size()));
    }

    /**
     * BGE 评分模拟 — 精确匹配导向.
     * <ul>
     *   <li>关键词精确匹配: 高权重 (0.5)</li>
     *   <li>位置加权: 靠前 chunk 加分 (0.2)</li>
     *   <li>确定性噪声: 模拟模型不确定性 (0.3)</li>
     * </ul>
     */
    private double computeBgeScore(String query, Chunk chunk, int position, Random rng) {
        String text = chunk.content() == null ? "" : chunk.content().toLowerCase();
        String q = query.toLowerCase();

        // 关键词精确匹配 (BGE 强项: 精确匹配敏感)
        String[] queryTokens = q.split("\\s+");
        int matches = 0;
        for (String token : queryTokens) {
            if (token.length() > 1 && text.contains(token)) matches++;
        }
        double matchRatio = queryTokens.length > 0 ? (double) matches / queryTokens.length : 0;
        double matchScore = matchRatio * 0.5;

        // 位置加权 (检索靠前 = 更可能相关)
        double positionScore = Math.max(0, 1.0 - position * 0.05) * 0.2;

        // 确定性噪声 (模拟模型不确定性, BGE 噪声较低)
        double noise = rng.nextDouble() * 0.3;

        return Math.min(1.0, matchScore + positionScore + noise);
    }
}
