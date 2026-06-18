package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.core.model.RetrievedChunk;
import io.github.yysf1949.rag.core.port.RetrievalPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 知识库检索工具 — Phase 17 重构走 {@link RetrievalPort} embed+search 4 步链,
 * Phase 18 P0 拆 3 个顶层 record 修 Spring AI 1.0.9 JsonParser 内部 record bug.
 *
 * <h2>Phase 17 ship 状态</h2>
 * <p>Phase 17 T3 ship 时 Request/Response/Chunk 是 KbSearchTool 内部 record.
 * T4 真实 DeepSeek E2E 跑出 ClassCastException — 根因 = Spring AI 1.0.9
 * {@code FunctionToolCallback.call(json)} 内部用 {@code JsonParser.fromJson(json, Type)}
 * 反序列化 {@code KbSearchTool$Request} (内部 record) 时, 生成 class 文件 path 含
 * {@code $}, JsonParser 处理有 bug 把 JSON 反成 String, 后续强转抛异常.</p>
 *
 * <h2>Phase 18 P0 修法</h2>
 * <p>把 Request/Response/Chunk 拆成 {@code io.github.yysf1949.rag.agent.builtin} 包下的
 * 顶层 record ({@link KbSearchRequest} / {@link KbSearchResponse} / {@link KbSearchChunk}).
 * 顶层 record class 文件无 {@code $}, Spring AI 1.0.9 JsonParser 处理正常.</p>
 *
 * <h2>为什么 L1_READ</h2>
 * <p>纯读操作, 不修改任何业务数据. Phase 14 authorizer 全 ctx 都过
 * (permissive / confirmed / awaitingConfirmation).</p>
 *
 * <h2>kbVersion 简化方案 → Phase 18 P2 升级</h2>
 * <p>Phase 17 §3.3: rag-core 还没有 KB version service, Request.kbVersion 用 long (默认 -1)
 * 表示"用最新已发布版本", tool 内部把 -1 转 0 (VectorStore.search 内部解析 0 = default version).</p>
 * <p>Phase 18 P2: 不再做 -1 → 0 的转换. KbSearchTool 透传 {@code request.kbVersion()} 给
 * {@link RetrievalPort#search}, 由 {@code RetrievalAdapter} 内部统一通过
 * {@code KbVersionService.resolveVersion} 解析 (跨 backend 一致). 默认 -1 行为不变.</p>
 */
@Component
@ConditionalOnBean(RetrievalPort.class)
public class KbSearchTool {

    private final RetrievalPort retrievalPort;

    public KbSearchTool(RetrievalPort retrievalPort) {
        this.retrievalPort = Objects.requireNonNull(retrievalPort, "retrievalPort");
    }

    @ToolSpec(
            name = "kb_search",
            description = "在租户知识库中检索相关文档片段, 返回结构化结果 (id/text/score/metadata)。"
                    + "纯读操作, 不修改业务数据; 适合回答用户关于产品/政策/规则的提问。"
                    + "调用时传 tenantId/kbId/query/topK/userPermissionTags (kbVersion=-1 表最新)。"
                    + "返回 chunks 由 LLM 自行合成 grounded 答案。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true)
    public KbSearchResponse search(KbSearchRequest request) {
        Objects.requireNonNull(request, "request");

        // Phase 18 P2: 透传 request.kbVersion(). RetrievalAdapter 内部统一处理
        // -1 → active version (跨 backend 一致 via KbVersionService).
        List<RetrievedChunk> retrieved = retrievalPort.search(
                request.tenantId(),
                request.kbId(),
                request.kbVersion(),
                request.query(),
                request.topK(),
                request.userPermissionTags());

        List<KbSearchChunk> chunks = retrieved.stream()
                .map(rc -> new KbSearchChunk(
                        rc.chunkId(),
                        rc.text(),
                        rc.score(),
                        rc.kbId(),
                        rc.kbVersion(),
                        rc.metadata()))
                .toList();
        return new KbSearchResponse(request.kbId(), request.query(), chunks.size(), chunks);
    }
}