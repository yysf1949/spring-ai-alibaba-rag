package io.github.yysf1949.rag.embedding.bcembedding;

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
 * BCEmbedding Reranker (BAAI/bce-reranker-base_v1) 模拟适配器 — Phase 41 T2 (R18).
 *
 * <p><b>Mock 模式</b>: 不调用真实模型 API, 用确定性评分函数模拟 BCEmbedding 的排序行为.
 * BCEmbedding 特点: 中文优化, 语义模糊匹配强, 精确匹配略弱于 BGE.</p>
 *
 * <h2>评分差异设计 (vs BGE)</h2>
 * <ul>
 *   <li>语义模糊匹配: 高权重 (0.4) — 模拟 BCEmbedding 中文语义理解优势</li>
 *   <li>字符级重叠: 中权重 (0.25) — 模拟 BCEmbedding 对近义词/同义词的容忍</li>
 *   <li>位置加权: 0.15</li>
 *   <li>噪声: 0.2 (略高于 BGE, 模拟 BCEmbedding 不确定性稍高)</li>
 * </ul>
 */
public class BcEmbeddingRerankService implements RerankService {

    private static final Logger log = LoggerFactory.getLogger(BcEmbeddingRerankService.class);

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
        Random rng = new Random(query.hashCode() ^ 0x5DEECE66DL); // 不同 seed = 不同行为

        for (int i = 0; i < candidates.size(); i++) {
            Chunk chunk = candidates.get(i);
            double score = computeBceScore(query, chunk, i, rng);
            scored.add(new RerankResult(chunk, score));
        }

        scored.sort(Comparator.comparingDouble(RerankResult::relevanceScore).reversed());
        return scored.subList(0, Math.min(topN, scored.size()));
    }

    /**
     * BCEmbedding 评分模拟 — 语义模糊匹配导向 (中文优化).
     */
    private double computeBceScore(String query, Chunk chunk, int position, Random rng) {
        String text = chunk.content() == null ? "" : chunk.content().toLowerCase();
        String q = query.toLowerCase();

        // 语义模糊匹配 (BCEmbedding 强项: 中文语义理解)
        // 模拟: 字符级 n-gram 重叠 (捕获中文分词差异)
        double semanticScore = computeNGramOverlap(q, text, 2) * 0.4;

        // 字符级重叠 (容忍近义词)
        double charOverlap = computeCharOverlap(q, text) * 0.25;

        // 位置加权
        double positionScore = Math.max(0, 1.0 - position * 0.05) * 0.15;

        // 噪声 (BCEmbedding 略高于 BGE)
        double noise = rng.nextDouble() * 0.2;

        return Math.min(1.0, semanticScore + charOverlap + positionScore + noise);
    }

    /** 2-gram 重叠率 — 捕获中文语义相似性 */
    private double computeNGramOverlap(String query, String text, int n) {
        if (query.length() < n || text.length() < n) return 0;
        var queryGrams = new java.util.HashSet<String>();
        for (int i = 0; i <= query.length() - n; i++) {
            queryGrams.add(query.substring(i, i + n));
        }
        int matches = 0;
        for (int i = 0; i <= text.length() - n; i++) {
            if (queryGrams.contains(text.substring(i, i + n))) matches++;
        }
        int totalGrams = Math.max(1, text.length() - n + 1);
        return (double) matches / totalGrams;
    }

    /** 单字符重叠率 — 模糊匹配 */
    private double computeCharOverlap(String query, String text) {
        if (query.isEmpty() || text.isEmpty()) return 0;
        var queryChars = new java.util.HashSet<>();
        for (char c : query.toCharArray()) queryChars.add(c);
        int matches = 0;
        for (char c : text.toCharArray()) {
            if (queryChars.contains(c)) matches++;
        }
        return (double) matches / Math.max(1, text.length());
    }
}
