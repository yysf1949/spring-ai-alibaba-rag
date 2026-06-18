package io.github.yysf1949.rag.agent.api;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;

/**
 * Agent 单次调用的输入。
 *
 * <p>对齐「路条编程」文章 §"查询 ≠ 执行，必须拆开"：
 * {@code toolName} + {@code requestPayload} + {@code idempotencyKey} 三者绑定。</p>
 *
 * @param identity        调用者身份
 * @param toolName        工具名（kebab-case）
 * @param requestPayload  业务请求 DTO（由编排层 JSON 序列化后再反序列化给工具）
 * @param idempotencyKey  幂等键（L2+ 必传；L1 可为 null）
 */
public record AgentRequest(
        AgentIdentity identity,
        String toolName,
        Object requestPayload,
        IdempotencyKey idempotencyKey
) {

    public static AgentRequest of(AgentIdentity identity, String toolName,
                                  Object requestPayload, IdempotencyKey key) {
        return new AgentRequest(identity, toolName, requestPayload, key);
    }
}