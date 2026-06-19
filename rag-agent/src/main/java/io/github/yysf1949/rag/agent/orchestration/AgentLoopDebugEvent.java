package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.action.RiskLevel;

/**
 * Agent Loop 调试事件 — 记录每步决策的完整上下文。
 *
 * <p>在 {@link DebugMode#RECORD} 或 {@link DebugMode#VERBOSE} 模式下，
 * 每次 {@link DefaultAgentLoop#execute} 调用会在关键决策点生成事件，
 * 供开发者回溯 Agent 的决策过程。</p>
 *
 * @param step           步骤编号（从 1 开始递增）
 * @param timestamp      事件发生的毫秒时间戳
 * @param phase          决策阶段：INTERPRET / SELECT_TOOLS / EXECUTE / VERIFY
 * @param toolName       选中的工具名（SELECT_TOOLS 阶段填充）
 * @param riskLevel      工具风险等级（SELECT_TOOLS / VERIFY 阶段填充）
 * @param toolArgs       工具参数（JSON 字符串，EXECUTE 阶段填充）
 * @param toolResult     工具结果（JSON 字符串，EXECUTE 阶段填充）
 * @param policyDecision 策略决策：ALLOW / DENY / WAIT_CONFIRM（VERIFY 阶段填充）
 * @param llmResponse    LLM 原始回复（INTERPRET 阶段填充，可为 null）
 */
public record AgentLoopDebugEvent(
        int step,
        long timestamp,
        Phase phase,
        String toolName,
        RiskLevel riskLevel,
        String toolArgs,
        String toolResult,
        String policyDecision,
        String llmResponse
) {

    /**
     * 调试事件的决策阶段。
     */
    public enum Phase {
        /** 解析用户意图 / 接收请求。 */
        INTERPRET,
        /** 从注册表中选择工具。 */
        SELECT_TOOLS,
        /** 执行工具调用。 */
        EXECUTE,
        /** 验证执行结果 / 策略决策。 */
        VERIFY
    }
}
