package io.github.yysf1949.rag.agent.api;

/**
 * Agent 单次调用的输出。
 *
 * @param toolName      工具名（与请求一致）
 * @param outcome       SUCCESS / FAILURE / DENIED
 * @param toolResponse  工具返回值（业务侧 DTO）
 * @param message       给用户的解释性文字（模型可选地包一层；本 Phase 直接吐 toolResponse.toString()）
 * @param latencyMs     端到端耗时（含治理层 + 工具本身）
 */
public record AgentResponse(
        String toolName,
        String outcome,
        Object toolResponse,
        String message,
        long latencyMs
) { }