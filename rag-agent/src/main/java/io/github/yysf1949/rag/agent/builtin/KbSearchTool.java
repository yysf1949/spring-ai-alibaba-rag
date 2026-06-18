package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.core.model.RetrievedChunk;
import io.github.yysf1949.rag.core.port.RetrievalPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 知识库检索工具 — Phase 17 重构, 走 {@link RetrievalPort} embed+search 4 步链。
 *
 * <h2>对比 Phase 14 旧实现</h2>
 * <ul>
 *   <li><b>旧版</b>: 依赖 {@code QAService} 8 步链 (rewrite→cache→embed→search→rerank→context→llm→cache),
 *       Request 必有 userId, Response 返 finalText (LLM 已合成答案)。</li>
 *   <li><b>新版</b>: 依赖 {@link RetrievalPort} 4 步链 (embed→search), 不做 rewrite/rerank/cache/fallback。
 *       Request 改用 (tenantId, kbId, kbVersion, query, topK, userPermissionTags), Response 返结构化
 *       chunks (id/text/score/kbId/kbVersion/metadata), 由 ChatClient 端的 LLM 自行合成 grounded 答案。</li>
 * </ul>
 *
 * <h2>为什么切到 RetrievalPort</h2>
 * <p>Plan §1.3 — agent 走工具控权, 检索深度收窄到 embed+search, 避免间接依赖 QAService 的
 * 8 步链 (那是给 RAG HTTP API 用的)。Phase 18 可在 RetrievalPort 上加 rerank/cache。</p>
 *
 * <h2>为什么 L1_READ</h2>
 * <p>纯读操作, 不修改任何业务数据。Phase 14 authorizer 全 ctx 都过 (permissive /
 * confirmed / awaitingConfirmation), 跟旧版一致。</p>
 *
 * <h2>kbVersion 简化方案</h2>
 * <p>Plan §3.3 — rag-core 还没有 KB version service, Request.kbVersion 用 long (默认 -1)
 * 表示"用最新已发布版本", tool 内部把 -1 转 0 (VectorStore.search 内部解析 0 = default version)。
 * Phase 18 加 KB version API 后替换。</p>
 *
 * <h2>多租户硬墙</h2>
 * <p>{@code tenantId} 是 Request 必填项 (kb_search 必须多租户硬墙), LLM 拼 tenantId 来自 ctx 注入
 * (Plan §3.3 tenant 注入说明)。</p>
 */
@Component
@ConditionalOnBean(RetrievalPort.class)
public class KbSearchTool {

    /** Phase 7 default topK (VectorStore.search spec §8.1); tool 默认 5 减少 LLM 上下文负担 */
    public static final int DEFAULT_TOPK = 5;
    /** Tool 内部 topK 上限 (避免 LLM 拼过大 topK 拖慢检索) */
    public static final int MAX_TOPK = 20;

    private final RetrievalPort retrievalPort;

    public KbSearchTool(RetrievalPort retrievalPort) {
        this.retrievalPort = Objects.requireNonNull(retrievalPort, "retrievalPort");
    }

    /**
     * Phase 17 — LLM 调 kb_search 时的入参 record (Plan §2.3)。
     *
     * <h2>字段说明</h2>
     * <ul>
     *   <li>{@code tenantId} — 必填, 多租户硬墙 (LLM 从 ctx 注入)</li>
     *   <li>{@code kbId} — 必填, 知识库 ID</li>
     *   <li>{@code kbVersion} — 必填, -1 = 用最新版本 (tool 内部转 0)</li>
     *   <li>{@code query} — 必填, 原始查询文本</li>
     *   <li>{@code topK} — 选填, 默认 5, 范围 [1, 20]</li>
     *   <li>{@code userPermissionTags} — 选填, 默认 [], Phase 18 推 ctx → tag 注入</li>
     * </ul>
     */
    public record Request(
            String tenantId,
            String kbId,
            long kbVersion,                  // -1 = latest (tool 内部转 0)
            String query,
            int topK,
            List<String> userPermissionTags
    ) {
        public Request {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId must not be blank");
            }
            if (kbId == null || kbId.isBlank()) {
                throw new IllegalArgumentException("kbId must not be blank");
            }
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("query must not be blank");
            }
            if (userPermissionTags == null) {
                userPermissionTags = List.of();
            }
            if (topK <= 0) {
                topK = DEFAULT_TOPK;
            } else if (topK > MAX_TOPK) {
                topK = MAX_TOPK;
            }
        }
    }

    /**
     * LLM 拿到的 JSON 形态 (Plan §2.4):
     * <pre>{@code
     * {
     *   "kbId": "default",
     *   "query": "退款政策",
     *   "total": 3,
     *   "chunks": [
     *     {"id":"c-1","text":"...","score":0.92,"kbId":"default","kbVersion":42,"metadata":{...}}
     *   ]
     * }
     * }</pre>
     */
    public record Response(
            String kbId,
            String query,
            int total,
            List<Chunk> chunks
    ) { }

    /** 对外披露的 chunk — id/text/score/kbId/kbVersion/metadata (与 plan §2.4 字段对齐) */
    public record Chunk(
            String id,
            String text,
            double score,
            String kbId,
            long kbVersion,
            Map<String, String> metadata
    ) { }

    @ToolSpec(
            name = "kb_search",
            description = "在租户知识库中检索相关文档片段, 返回结构化结果 (id/text/score/metadata)。"
                    + "纯读操作, 不修改业务数据; 适合回答用户关于产品/政策/规则的提问。"
                    + "调用时传 tenantId/kbId/query/topK/userPermissionTags (kbVersion=-1 表最新)。"
                    + "返回 chunks 由 LLM 自行合成 grounded 答案。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true)
    public Response search(Request request) {
        Objects.requireNonNull(request, "request");
        // 简化方案: kbVersion = -1 → 0 (VectorStore.search 内部解析为 default version)
        long effectiveKbVersion = request.kbVersion() < 0 ? 0L : request.kbVersion();

        List<RetrievedChunk> retrieved = retrievalPort.search(
                request.tenantId(),
                request.kbId(),
                effectiveKbVersion,
                request.query(),
                request.topK(),
                request.userPermissionTags());

        List<Chunk> chunks = retrieved.stream()
                .map(rc -> new Chunk(
                        rc.chunkId(),
                        rc.text(),
                        rc.score(),
                        rc.kbId(),
                        rc.kbVersion(),
                        rc.metadata()))
                .toList();
        return new Response(request.kbId(), request.query(), chunks.size(), chunks);
    }
}