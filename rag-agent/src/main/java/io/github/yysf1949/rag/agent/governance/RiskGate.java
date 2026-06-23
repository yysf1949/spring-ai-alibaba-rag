package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.ToolDescriptor;

/**
 * 风险门控接口。
 *
 * <h2>Phase 10 改造</h2>
 * <p>{@code check} 加第 4 参数 {@code requestedAmountCents} — 用于 L3 金额门控。
 * 如果工具没有金额概念（如查询类），传 {@code null}。</p>
 */
public interface RiskGate {
    void check(ToolDescriptor descriptor, AgentIdentity identity,
               IdempotencyKey idempotencyKey, Long requestedAmountCents);
}
