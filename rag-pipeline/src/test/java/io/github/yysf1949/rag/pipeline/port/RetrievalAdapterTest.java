package io.github.yysf1949.rag.pipeline.port;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.PermissionMode;
import io.github.yysf1949.rag.core.model.RetrievedChunk;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 17 T4 — {@link RetrievalAdapter} 单测（mock EmbeddingGateway + VectorStore）。
 *
 * <h2>用例（plan §3.4）</h2>
 * <ol>
 *   <li>embed + search 串通：mock 1 个 chunk，验证 RetrievedChunk 字段映射 + score [0,1]</li>
 *   <li>EmbeddingGateway 返空 → 抛 IllegalStateException（adapter 内显式 guard）</li>
 *   <li>VectorStore 返多 chunk + 各种 metadata → 正确映射 RetrievedChunk 列表</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetrievalAdapter")
class RetrievalAdapterTest {

    private static final int DIM = 16;
    private static final String TENANT = "t1";
    private static final String KB = "default";
    private static final long KB_VERSION = 0L;

    @Mock EmbeddingGateway embeddingGateway;
    @Mock VectorStore vectorStore;
    RetrievalAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RetrievalAdapter(vectorStore, embeddingGateway);
    }

    @Test
    @DisplayName("happy path: embed→search→RetrievedChunk 字段映射 + score [0,1]")
    void happyPath() {
        // 1. query embedding: 全 1.0 (cosine 1.0 → score 1.0)
        float[] queryVec = new float[DIM];
        for (int i = 0; i < DIM; i++) queryVec[i] = 1.0f;
        when(embeddingGateway.dimension()).thenReturn(DIM);
        when(embeddingGateway.embedBatch(List.of("退款政策")))
                .thenReturn(List.of(queryVec));

        // 2. VectorStore 返 1 个 chunk（embedding 跟 query 完全相同 → cosine=1.0）
        float[] chunkVec = new float[DIM];
        for (int i = 0; i < DIM; i++) chunkVec[i] = 1.0f;
        Chunk c1 = stubChunk("c-1", "退款 7 天无理由", chunkVec, "1",
                Instant.parse("2026-06-18T10:00:00Z"));
        when(vectorStore.search(eq(queryVec), eq(TENANT), eq(KB), eq(KB_VERSION),
                anyList(), any(PermissionMode.class), eq(5)))
                .thenReturn(List.of(c1));

        // 3. 调（kbVersion=0 透传给 VectorStore，由它解析默认版本）
        List<RetrievedChunk> out = adapter.search(TENANT, KB, KB_VERSION, "退款政策", 5, List.of());

        // 4. 验证
        assertEquals(1, out.size());
        RetrievedChunk rc = out.get(0);
        assertEquals("c-1", rc.chunkId());
        assertEquals("退款 7 天无理由", rc.text());
        assertEquals(KB, rc.kbId());
        // kbVersion 来源: Chunk.documentVersion=1 → parseLong=1（adapter 内部从 chunk 解析）
        assertEquals(1L, rc.kbVersion());
        assertEquals(1.0, rc.score(), 1e-9, "cosine=1.0 归一化后应=1.0");
        // metadata 5 个字段
        assertEquals("退款政策", rc.metadata().get("title"));
        assertEquals("https://example.com/c-1", rc.metadata().get("sourceUri"));
        assertEquals("退款/规则", rc.metadata().get("sectionPath"));
        assertEquals("1", rc.metadata().get("documentVersion"));
        assertEquals("doc-c-1", rc.metadata().get("documentId"));
    }

    @Test
    @DisplayName("EmbeddingGateway 返空 → 抛 IllegalStateException")
    void emptyEmbeddingThrows() {
        when(embeddingGateway.embedBatch(List.of("退款政策")))
                .thenReturn(List.of());

        assertThrows(IllegalStateException.class,
                () -> adapter.search(TENANT, KB, KB_VERSION, "退款政策", 5, List.of()),
                "empty vector");
    }

    @Test
    @DisplayName("多 chunk + 多种 metadata → 列表正确映射 + 验证 AND 模式 + VectorStore 入参")
    void multipleChunks() {
        // 1. query embedding: 全 1.0
        float[] queryVec = new float[DIM];
        for (int i = 0; i < DIM; i++) queryVec[i] = 1.0f;
        when(embeddingGateway.dimension()).thenReturn(DIM);
        when(embeddingGateway.embedBatch(List.of("运费险"))).thenReturn(List.of(queryVec));

        // 2. VectorStore 返 3 chunk（embedding 跟 query 完全相同 → cosine=1.0）
        Instant now = Instant.parse("2026-06-18T10:00:00Z");
        float[] chunkVec = new float[DIM];
        for (int i = 0; i < DIM; i++) chunkVec[i] = 1.0f;
        Chunk c1 = stubChunk("c-1", "运费险 9 元", chunkVec, "5", now);
        Chunk c2 = stubChunk("c-2", "退货 7 天", chunkVec, "5", now);
        Chunk c3 = stubChunk("c-3", "换货 15 天", chunkVec, "5", now);
        ArgumentCaptor<List<String>> tagsCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<PermissionMode> modeCap = ArgumentCaptor.forClass(PermissionMode.class);
        ArgumentCaptor<Integer> topKCap = ArgumentCaptor.forClass(Integer.class);
        when(vectorStore.search(eq(queryVec), eq(TENANT), eq(KB), eq(KB_VERSION),
                tagsCap.capture(), modeCap.capture(), topKCap.capture()))
                .thenReturn(List.of(c1, c2, c3));

        // 3. 调（带 userPermissionTags = ["vip"]）
        List<RetrievedChunk> out = adapter.search(
                TENANT, KB, KB_VERSION, "运费险", 3, List.of("vip"));

        // 4. 验证 VectorStore 入参
        assertEquals(List.of("vip"), tagsCap.getValue());
        assertEquals(PermissionMode.AND, modeCap.getValue());
        assertEquals(3, topKCap.getValue());

        // 5. 验证输出：3 chunk, score 全部 1.0
        assertEquals(3, out.size());
        assertEquals("c-1", out.get(0).chunkId());
        assertEquals("c-2", out.get(1).chunkId());
        assertEquals("c-3", out.get(2).chunkId());
        // embedding 跟 query 完全相同 → cosine=1.0 → score=1.0
        assertTrue(out.stream().allMatch(rc -> Math.abs(rc.score() - 1.0) < 1e-9),
                "embedding 跟 query 完全相同 → cosine=1.0 → score=1.0");
        // documentVersion='5' → kbVersion=5L
        assertTrue(out.stream().allMatch(rc -> rc.kbVersion() == 5L),
                "documentVersion='5' → kbVersion=5L");
    }

    @Test
    @DisplayName("embedding 维度不匹配 → 抛 IllegalStateException")
    void dimensionMismatchThrows() {
        // 1. gateway 声明 1536 dim, 但实际返 16 维
        when(embeddingGateway.dimension()).thenReturn(1536);
        when(embeddingGateway.embedBatch(anyList()))
                .thenReturn(List.of(new float[16]));

        assertThrows(IllegalStateException.class,
                () -> adapter.search(TENANT, KB, KB_VERSION, "x", 5, List.of()),
                "dimension mismatch");
    }

    @Test
    @DisplayName("入参校验: tenantId/kbId/query 空 → IllegalArgumentException")
    void inputValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.search("", KB, 0, "q", 5, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.search(TENANT, "", 0, "q", 5, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.search(TENANT, KB, 0, "", 5, List.of()));
    }

    // ─── helpers ────────────────────────────────────────────────────────

    /**
     * 跟 IngestServiceImplTest.stubChunk 同款结构。
     *
     * @param id             chunkId
     * @param content        chunk 文本
     * @param embedding      embedding 向量
     * @param docVersion     documentVersion（也作为 RetrievedChunk.kbVersion 来源）
     * @param publishedAt    发布时间戳
     */
    private static Chunk stubChunk(String id, String content, float[] embedding,
                                   String docVersion, Instant publishedAt) {
        return new Chunk(
                id, TENANT, KB,
                "doc-" + id, docVersion,
                content.contains("退款") ? "退款政策" : "通用条款",
                content.contains("退款") ? "退款/规则" : "通用",
                content, Set.of(), ChunkStatus.ACTIVE, publishedAt,
                "https://example.com/" + id,
                embedding, null);
    }
}