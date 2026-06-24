package io.github.yysf1949.rag.eval;

import io.github.yysf1949.rag.core.model.Chunk;

import java.util.List;
import java.util.Map;

/**
 * 单条 Rerank 评测数据 — 一个 query + 候选 chunks + ground truth relevance.
 *
 * @param query        查询文本
 * @param candidates   候选 chunk 列表 (通常 10 条)
 * @param relevance    chunkId → relevance score (0=不相关, 4=完全相关)
 */
public record RerankEvalFixture(
        String query,
        List<Chunk> candidates,
        Map<String, Integer> relevance
) {
    /**
     * 获取某 chunk 的 ground truth relevance.
     *
     * @param chunkId chunk ID
     * @return relevance 0-4 (不存在则 0)
     */
    public int relevance(String chunkId) {
        return relevance.getOrDefault(chunkId, 0);
    }

    /**
     * 候选 chunk ID 列表.
     */
    public List<String> candidateIds() {
        return candidates.stream().map(Chunk::chunkId).toList();
    }

    /**
     * 候选 chunk 列表 (用于 RerankService 输入).
     */
    public List<Chunk> candidateChunks() {
        return candidates;
    }
}
