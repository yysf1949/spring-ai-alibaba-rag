package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.KbVersion;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.QAService;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库检索工具 — L1 只读。包装现有 {@code QAService}。
 *
 * <h2>为什么是 L1</h2>
 * <p>{@code QAService.answer} 不会修改任何业务数据 — 检索、rerank、LLM
 * 生成都是只读操作。文章 4 级里 L1 是"查询订单、查询物流、查询退款规则"
 * 之类，知识库查询天然属于 L1。</p>
 *
 * <h2>为什么 idem 默认为 true</h2>
 * <p>纯读，不改任何状态，重复调用结果一致（除非 KB 版本切换，本 Phase 暂不处理）。</p>
 */
@Component
public class KbSearchTool {

    private final QAService qaService;

    public KbSearchTool(QAService qaService) {
        this.qaService = qaService;
    }

    public record Request(
            String tenantId,
            String userId,
            String rawText,
            Set<String> permissionTags,
            int topK,
            Long kbVersion
    ) {
        public Request {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId required");
            }
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId required");
            }
            if (rawText == null || rawText.isBlank()) {
                throw new IllegalArgumentException("rawText required");
            }
            if (permissionTags == null) permissionTags = Set.of();
            if (topK <= 0) topK = 5;
        }
    }

    public record Response(
            String answerText,
            AnswerSource source,
            List<String> retrievedChunkIds
    ) { }

    @ToolSpec(
            name = "kb_search",
            description = "在租户知识库中检索相关文档片段并由 LLM 合成答案。"
                    + "纯读操作，不修改任何业务数据；适合回答用户关于产品/政策/规则的提问。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true)
    public Response search(Request request) {
        // Request.kbVersion 是 Long（业务层友好），内部转 KbVersion。
        // KbVersion 还需要 (kbId) — 工具不知道是哪个 kb，固化为"default"。
        // 真实场景下应该从 AgentIdentity 上下文/配置注入 kbId；本 Phase 暂用"default"。
        KbVersion kbVer = request.kbVersion() == null
                ? null
                : new KbVersion(request.tenantId(), "default", request.kbVersion());

        Query q = new Query(
                request.tenantId(),
                request.userId(),
                null, // sessionId 由 AgentIdentity 透传，本工具不重复
                request.rawText(),
                new HashSet<>(request.permissionTags()),
                request.topK(),
                kbVer);
        Answer answer = qaService.answer(q);
        return new Response(answer.finalText(), answer.source(), List.of());
    }
}
