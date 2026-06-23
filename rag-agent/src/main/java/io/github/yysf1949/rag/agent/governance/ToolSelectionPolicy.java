package io.github.yysf1949.rag.agent.governance;

import java.util.List;

/**
 * 工具选择策略接口 — 根据用户身份、会话状态等维度动态过滤候选工具。
 *
 * <h2>对齐「路条编程」文章 §"应用程序应该根据当前场景、用户身份和会话状态，
 * 动态决定本次请求可以使用哪些工具"</h2>
 *
 * <p>多个策略实现通过 Spring {@code List<ToolSelectionPolicy>} 注入
 * {@link StageAwareToolAuthorizer}，依次过滤，取交集。</p>
 */
public interface ToolSelectionPolicy {

    /**
     * 根据上下文过滤候选工具列表，返回仍然允许的工具子集。
     *
     * @param ctx             工具选择上下文（不可变快照）
     * @param candidateTools  当前候选工具列表（已经过 riskLevel 过滤）
     * @return 过滤后的工具列表（顺序保持）
     */
    List<String> filterTools(ToolSelectionContext ctx, List<String> candidateTools);

    /**
     * 工具选择上下文 — 封装本次请求的用户身份、会话状态和意图信息。
     *
     * @param tenantId        租户 ID
     * @param userId          用户 ID
     * @param conversationId  会话 ID
     * @param membershipTier  会员等级 (NORMAL / GOLD / PLATINUM, null = 未注册)
     * @param messageCount    当前会话已发送消息数（含本轮）
     * @param lastToolCalled  上一次调用的工具名（可为 null）
     * @param userIntent      用户意图分类
     */
    record ToolSelectionContext(
            String tenantId,
            String userId,
            String conversationId,
            String membershipTier,
            int messageCount,
            String lastToolCalled,
            UserIntent userIntent
    ) {}

    /**
     * 用户意图分类 — 决定工具开放范围的关键信号。
     */
    enum UserIntent {
        /** 查询类：查订单、查物流、查规则 */
        QUERY,
        /** 执行类：取消订单、退款、改价 */
        EXECUTE,
        /** 投诉类：不满、要求升级 */
        COMPLAINT,
        /** 咨询类：了解产品、问规则 */
        INQUIRY
    }
}
