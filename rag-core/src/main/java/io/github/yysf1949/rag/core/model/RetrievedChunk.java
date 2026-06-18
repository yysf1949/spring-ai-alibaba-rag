package io.github.yysf1949.rag.core.model;

import java.util.Map;

/**
 * 检索结果的单条 chunk — Phase 17 新增，给 Agent 工具层用。
 *
 * <p>与 {@link Chunk} 的区别：{@code Chunk} 是写入侧的完整模型（含 embedding/status/
 * permissionTags 等内部字段），{@code RetrievedChunk} 是检索侧的对外模型，只携带
 * LLM 生成回答所需的最少字段 + score（归一化 0-1，值越大越相关）。</p>
 *
 * <p>{@code metadata} 来自 {@code Chunk} 的 title/sectionPath/sourceUri/documentVersion
 * 等"可对外披露"的字段，由实现（{@code RetrievalAdapter}）映射填充。</p>
 *
 * @param chunkId    唯一 ID（与 {@code Chunk.chunkId} 对齐）
 * @param text       chunk 文本内容（对应 {@code Chunk.content}）
 * @param score      相似度 0-1（{@code RetrievalAdapter} 从 cosine 归一化得出）
 * @param kbId       知识库 ID（冗余存，方便 LLM 引用）
 * @param kbVersion  版本号（冗余存，方便调试"是不是当前版本的 KB"）
 * @param metadata   附加元数据（来源 URL / section / title 等）
 */
public record RetrievedChunk(
        String chunkId,
        String text,
        double score,
        String kbId,
        long kbVersion,
        Map<String, String> metadata
) {

    public RetrievedChunk {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId must not be blank");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("kbId must not be blank");
        }
        if (Double.isNaN(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException(
                    "score must be in [0.0, 1.0], got " + score);
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}