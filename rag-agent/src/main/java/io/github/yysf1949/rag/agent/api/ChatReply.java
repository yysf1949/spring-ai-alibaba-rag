package io.github.yysf1949.rag.agent.api;

/**
 * ChatClientService 对话响应 — Phase 16 AgentController /api/agent/chat 默认 JSON 模式返回值.
 *
 * <p>与 {@link AgentResponse} 的关系: AgentResponse 是"单 tool 反射调用"链路产物;
 * ChatReply 是"LLM 自由对话 + 自动选 tool"链路产物, 两者平行不互替.</p>
 *
 * @param content         LLM 最终文本响应 (AssistantMessage.getText)
 * @param conversationId  本次对话 ID — 客户端可在下次请求时通过 X-Session-Id 复用, 实现多轮
 */
public record ChatReply(String content, String conversationId) { }