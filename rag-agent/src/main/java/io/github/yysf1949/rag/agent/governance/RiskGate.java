package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.ToolDescriptor;

/**
 * 风险门控 — 编排层在调用工具前必须过 RiskGate.check()。
 *
 * <p>对齐「路条编程」文章 §"工具分级"：
 * "工具分级的价值，是把大模型的不确定性限制在可控范围内。"</p>
 */
public interface RiskGate {

    /**
     * @param descriptor  工具元数据
     * @param identity    调用者
     * @param idemKey     幂等键（L2+ 写操作必传；L1 可为 null）
     * @throws io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException 风险被拒
     */
    void check(ToolDescriptor descriptor, AgentIdentity identity, IdempotencyKey idemKey);
}
