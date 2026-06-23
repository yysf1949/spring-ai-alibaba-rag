package io.github.yysf1949.rag.agent.governance;

/**
 * 一次工具调用的完整上下文 — 编排层构造，治理层消费，审计钩子记录。
 *
 * @param identity   调用者身份
 * @param toolName   工具名（来自 {@code ToolDescriptor.name}）
 * @param requestJson  请求 JSON（治理层审计用，不重复序列化）
 * @param responseJson 响应 JSON
 * @param latencyMs  实际执行耗时
 * @param outcome    SUCCESS / FAILURE / DENIED
 */
public record ToolInvocationContext(
        AgentIdentity identity,
        String toolName,
        String requestJson,
        String responseJson,
        long latencyMs,
        String outcome
) { }
