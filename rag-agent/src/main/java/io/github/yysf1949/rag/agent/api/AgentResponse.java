package io.github.yysf1949.rag.agent.api;

/**
 * Agent 单次调用的输出。
 *
 * <p>Phase 10 改造：{@code outcome} 字段从 String 升级为 {@link AgentOutcome} enum，
 * 添加 {@code handoffContext} 字段（HANDOFF_REQUIRED 时携带待人工处理的上下文）。</p>
 *
 * @param toolName        工具名（与请求一致）
 * @param outcome         终态 / 非终态
 * @param toolResponse    工具返回值（业务侧 DTO）
 * @param message         给用户的解释性文字
 * @param latencyMs       端到端耗时
 * @param handoffContext  转人工上下文（HANDOFF_REQUIRED 时非空，含已查信息/规则匹配结果）
 */
public record AgentResponse(
        String toolName,
        AgentOutcome outcome,
        Object toolResponse,
        String message,
        long latencyMs,
        HandoffContextPayload handoffContext
) {

    public record HandoffContextPayload(
            String reason,
            String nextChannel,
            String summary,
            String toolChainJson
    ) { }
}
