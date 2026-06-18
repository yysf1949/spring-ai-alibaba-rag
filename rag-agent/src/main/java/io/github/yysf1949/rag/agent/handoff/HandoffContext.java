package io.github.yysf1949.rag.agent.handoff;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;

import java.util.List;

/**
 * 转人工上下文 — Agent 把已经做完的工作打包给人工客服。
 *
 * <h2>对齐「路条编程」文章 §"人工确认不是失败"</h2>
 * <p>理想 Agent 转人工前应该完成：</p>
 * <ol>
 *   <li>用户身份确认 — {@link AgentIdentity} 携带</li>
 *   <li>订单/工单信息查询 — {@code toolChain} 记录调过的工具</li>
 *   <li>问题分类 — {@code category}</li>
 *   <li>规则匹配 — {@code matchedRules}</li>
 *   <li>风险说明 — {@code riskNote}</li>
 * </ol>
 *
 * <p>人工客服接手后不需要重新问用户"你刚才发生了什么"。</p>
 */
public record HandoffContext(
        AgentIdentity identity,
        String toolName,
        HandoffReason reason,
        HandoffChannel channel,
        String summary,
        String category,
        List<String> matchedRules,
        String riskNote,
        List<String> toolChain,
        String toolChainJson
) {
    public static HandoffContext forAmountLimit(
            AgentIdentity identity, String toolName,
            long amountCents, long limitCents, List<String> toolChain) {
        return new HandoffContext(
                identity, toolName,
                HandoffReason.AMOUNT_LIMIT_EXCEEDED,
                HandoffChannel.WORK_ORDER,
                String.format("User requested %s with amount %d cents (limit %d). Manual review required.",
                        toolName, amountCents, limitCents),
                "HIGH_VALUE_TRANSACTION",
                List.of("amount_exceeds_l3_limit"),
                String.format("Amount %d cents is %d cents over the L3 limit. Fraud risk: low (verified user).",
                        amountCents, amountCents - limitCents),
                toolChain,
                String.join(" -> ", toolChain));
    }

    public static HandoffContext forInsufficientPrivilege(
            AgentIdentity identity, String toolName, List<String> toolChain) {
        return new HandoffContext(
                identity, toolName,
                HandoffReason.INSUFFICIENT_PRIVILEGE,
                HandoffChannel.LIVE_CHAT,
                String.format("User (roles=%s) attempted L4 tool [%s]. Admin approval required.",
                        identity.roles(), toolName),
                "PRIVILEGE_ESCALATION",
                List.of("l4_requires_admin_role"),
                "User is not in admin role. L4 tool execution requires manual approval.",
                toolChain,
                String.join(" -> ", toolChain));
    }
}
