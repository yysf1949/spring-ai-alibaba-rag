package io.github.yysf1949.rag.core.port;

import io.github.yysf1949.rag.core.model.RetrievedChunk;

import java.util.List;

/**
 * 检索端口 — Phase 17 新增，给 Agent 工具层（{@code KbSearchTool}）调用，
 * 跳过 QAService 的 8 步链（rewrite/rerank/cache/fallback），只做 embed + search。
 *
 * <h2>设计动机</h2>
 * <p>Phase 16 ship 后，{@code /api/agent/chat} 走 ChatClient → LLM 选 tool，但缺少 RAG
 * 检索能力。Phase 17 在 rag-core 暴露本端口，rag-pipeline 实现，rag-agent 用它包
 * {@code KbSearchTool} 让 LLM 能选它来检索知识库。</p>
 *
 * <h2>为什么不直接调 QAService</h2>
 * <ul>
 *   <li>QAService 8 步链是给 RAG HTTP API 用的（rag-pipeline 出口），agent 走工具控权更合适</li>
 *   <li>Phase 18 可以在本端口上加 rerank/cache，不影响 agent 调用点</li>
 *   <li>避免 rag-agent → rag-pipeline 跨层依赖（保持 agent→core←pipeline 分层方向）</li>
 * </ul>
 *
 * <h2>多租户硬墙</h2>
 * <p>实现必须按 {@code tenantId} 过滤（与 {@link VectorStore#search} 一致），
 * 跨租户访问视为 bug 而非业务异常。</p>
 */
public interface RetrievalPort {

    /**
     * Embed + 向量检索，简化版 8 步链（不含 rewrite/rerank/cache/fallback）。
     *
     * @param tenantId          租户硬墙（实现内部也校验）
     * @param kbId              知识库 ID
     * @param kbVersion         知识库版本（0 = 默认最新，由实现解析；KbSearchTool 内部把 -1 转 0）
     * @param query             原始查询文本（KbSearchTool 收到 LLM 拼过的 query）
     * @param topK              返回 chunk 数上限（Phase 7 默认 20；KbSearchTool 默认 5）
     * @param userPermissionTags 权限标签过滤（AND 模式，spec §8.2）
     * @return 按相似度 DESC 排序、限 topK 的 chunk 列表（每条带 0-1 normalized score）
     */
    List<RetrievedChunk> search(
            String tenantId,
            String kbId,
            long kbVersion,
            String query,
            int topK,
            List<String> userPermissionTags
    );
}