package io.github.yysf1949.rag.embedding.eval;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.embedding.bge.BgeRerankService;
import io.github.yysf1949.rag.embedding.bcembedding.BcEmbeddingRerankService;
import io.github.yysf1949.rag.embedding.stub.StubRerankService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rerank A/B 选型评测测试 — Phase 41 T2 (R18).
 */
class RerankBenchmarkTest {

    private Chunk makeChunk(String id, String text) {
        return new Chunk(
                id, "tenant-A", "kb-1", "doc-1", "1",
                "title", "summary", text,
                Set.of("ROLE_USER"), ChunkStatus.ACTIVE,
                Instant.now(), null, new float[16], null);
    }

    private RerankBenchmark.EvalItem makeItem(String query, String[][] chunks) {
        // chunks: [{chunkId, text, relevance}]
        List<Chunk> candidates = new ArrayList<>();
        Map<String, Integer> labels = new HashMap<>();
        for (String[] c : chunks) {
            candidates.add(makeChunk(c[0], c[1]));
            labels.put(c[0], Integer.parseInt(c[2]));
        }
        return new RerankBenchmark.EvalItem(query, candidates, labels);
    }

    @Test
    @DisplayName("BGE Reranker: 评分区间 [0,1], 排序合理")
    void bgeScoringAndRanking() {
        var service = new BgeRerankService();
        List<Chunk> candidates = List.of(
                makeChunk("c1", "退款规则 全额退款 七日内"),
                makeChunk("c2", "运费条款 物流配送"),
                makeChunk("c3", "退款 退款申请 退款流程")
        );
        var result = service.rerankWithScores("退款规则", candidates, 3);

        assertEquals(3, result.size());
        for (var r : result) {
            assertTrue(r.relevanceScore() >= 0 && r.relevanceScore() <= 1,
                    "评分应在 [0,1] 区间: " + r.relevanceScore());
        }
        // 含 "退款规则" 的 c1 和 c3 应排在 c2 前面
        String topId = result.get(0).chunk().chunkId();
        assertTrue(topId.equals("c1") || topId.equals("c3"), "top-1 应为含 '退款' 的 chunk");
    }

    @Test
    @DisplayName("BCEmbedding Reranker: 评分区间 [0,1], 排序合理")
    void bceScoringAndRanking() {
        var service = new BcEmbeddingRerankService();
        List<Chunk> candidates = List.of(
                makeChunk("c1", "退款规则 全额退款 七日内"),
                makeChunk("c2", "运费条款 物流配送"),
                makeChunk("c3", "退款 退款申请 退款流程")
        );
        var result = service.rerankWithScores("退款规则", candidates, 3);

        assertEquals(3, result.size());
        for (var r : result) {
            assertTrue(r.relevanceScore() >= 0 && r.relevanceScore() <= 1,
                    "评分应在 [0,1] 区间: " + r.relevanceScore());
        }
    }

    @Test
    @DisplayName("A/B 评测: 3 个模型跑通, 报告含 nDCG/P@1/MRR")
    void benchmarkProducesReport() {
        List<RerankBenchmark.EvalItem> dataset = List.of(
                makeItem("退款规则", new String[][]{
                        {"c1", "退款规则 全额退款 七日内", "2"},
                        {"c2", "运费条款 物流配送", "0"},
                        {"c3", "退款 退款申请 退款流程", "1"},
                        {"c4", "商品评价 用户反馈", "0"},
                        {"c5", "退款条件 退货政策", "2"},
                }),
                makeItem("物流配送", new String[][]{
                        {"c1", "退款规则 全额退款", "0"},
                        {"c2", "运费 物流 配送 时效", "2"},
                        {"c3", "物流查询 快递追踪", "2"},
                        {"c4", "商品评价", "0"},
                        {"c5", "配送范围 偏远地区", "1"},
                })
        );

        Map<String, io.github.yysf1949.rag.core.port.RerankService> models = new LinkedHashMap<>();
        models.put("Stub (no-op)", new StubRerankService());
        models.put("BGE (bge-reranker-v2-m3)", new BgeRerankService());
        models.put("BCEmbedding (bce-reranker-base)", new BcEmbeddingRerankService());

        RerankBenchmark benchmark = new RerankBenchmark();
        String report = benchmark.runBenchmark(dataset, models);

        assertNotNull(report);
        assertTrue(report.contains("nDCG@5"), "报告应含 nDCG@5");
        assertTrue(report.contains("Precision@1"), "报告应含 Precision@1");
        assertTrue(report.contains("MRR"), "报告应含 MRR");
        assertTrue(report.contains("BGE"), "报告应含 BGE 模型");
        assertTrue(report.contains("BCEmbedding"), "报告应含 BCEmbedding 模型");
        assertTrue(report.contains("推荐"), "报告应含推荐结论");
        // 打印报告供人工审核
        System.out.println(report);
    }

    @Test
    @DisplayName("BGE 和 BCEmbedding 评分应不同 (模拟不同模型行为)")
    void bgeAndBceProduceDifferentRankings() {
        List<Chunk> candidates = List.of(
                makeChunk("c1", "退款规则 全额退款 七日内"),
                makeChunk("c2", "退 款 规 则"),
                makeChunk("c3", "退款规则 退款流程 退款条件 退款申请")
        );

        var bge = new BgeRerankService().rerankWithScores("退款规则", candidates, 3);
        var bce = new BcEmbeddingRerankService().rerankWithScores("退款规则", candidates, 3);

        // 两个模型的评分应该不完全相同 (不同的评分函数 + 不同的 RNG seed)
        boolean anyDiff = false;
        for (int i = 0; i < bge.size(); i++) {
            if (Math.abs(bge.get(i).relevanceScore() - bce.get(i).relevanceScore()) > 0.001) {
                anyDiff = true;
                break;
            }
        }
        assertTrue(anyDiff, "BGE 和 BCEmbedding 应产生不同评分");
    }

    @Test
    @DisplayName("空候选列表返回空结果")
    void emptyCandidatesReturnsEmpty() {
        var bge = new BgeRerankService();
        var bce = new BcEmbeddingRerankService();

        assertTrue(bge.rerank("test", List.of(), 5).isEmpty());
        assertTrue(bce.rerank("test", List.of(), 5).isEmpty());
        assertTrue(bge.rerankWithScores("test", List.of(), 5).isEmpty());
        assertTrue(bce.rerankWithScores("test", List.of(), 5).isEmpty());
    }
}
