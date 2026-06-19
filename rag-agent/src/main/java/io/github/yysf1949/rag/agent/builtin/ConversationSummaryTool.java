package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 会话摘要工具 — L1 只读。
 *
 * <p>对会话消息列表生成摘要，提取关键问题。用于会话结束时的总结归档。</p>
 */
@Component
public class ConversationSummaryTool {

    @ToolSpec(
            name = "generate_conversation_summary",
            description = "根据消息列表生成会话摘要，返回summary/keyIssues/messageCount。适用于：会话结束时归档、用户问'帮我总结一下刚才的对话'。只读工具。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public SummaryResponse generateSummary(SummaryRequest req) {
        Objects.requireNonNull(req, "req");

        if (req.messages() == null || req.messages().isEmpty()) {
            return new SummaryResponse("无消息记录", List.of(), 0);
        }

        // 提取关键问题：包含"?"或常见关键词的消息
        List<String> keyIssues = new ArrayList<>();
        for (String msg : req.messages()) {
            if (msg.contains("?") || msg.contains("？")
                    || msg.contains("退款") || msg.contains("投诉")
                    || msg.contains("物流") || msg.contains("问题")
                    || msg.contains("不满") || msg.contains("延迟")) {
                keyIssues.add(msg);
            }
        }

        // 生成摘要
        String summary = "会话共 " + req.messages().size() + " 条消息"
                + (keyIssues.isEmpty() ? "，无明显关键问题" : "，涉及 " + keyIssues.size() + " 个关键问题");

        return new SummaryResponse(summary, keyIssues, req.messages().size());
    }

    public record SummaryRequest(List<String> messages) {}

    public record SummaryResponse(
            String summary,
            List<String> keyIssues,
            int messageCount
    ) {}
}
