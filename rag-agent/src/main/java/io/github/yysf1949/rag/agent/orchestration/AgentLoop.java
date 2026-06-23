package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;

/**
 * 单次工具调用循环 — 找到 tool → 过风险门 → 调 → 审计。
 *
 * <p>本 Phase 不实现"模型选 tool"那一步 — 由 {@code SpringAiAgentAdapter}
 * 在 LLM 拿到 tool_calls 后调用 {@code AgentLoop.execute}。
 * 真正的"模型迭代循环"（重试、改 tool）由 Spring AI 1.0.9 的
 * {@code FunctionCallingCallback} 处理。</p>
 */
public interface AgentLoop {
    AgentResponse execute(AgentRequest request);
}