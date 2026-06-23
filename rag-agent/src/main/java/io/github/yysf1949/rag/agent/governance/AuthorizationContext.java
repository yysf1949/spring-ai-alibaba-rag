package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;

import java.util.Set;

/**
 * 工具授权上下文 — 描述"本次 Agent 调用, 用户处于什么阶段 + 允许看到哪些 tool"。
 *
 * <h2>对齐「路条编程」文章 §"工具不是越多越好, 权限也不是一次性全部交给模型"</h2>
 * <p>文章原话: 应用程序应根据当前场景/用户身份/会话状态, 动态决定本次请求
 * 可以使用哪些工具。渐进式授权示例:
 * <ol>
 *   <li>"询问规则"阶段 → 只提供 L1 READ 工具</li>
 *   <li>"明确动作"阶段 → 加上 L2 REVERSIBLE</li>
 *   <li>"用户已确认"阶段 → 加上 L3/L4 (受 L4 审批限制)</li>
 * </ol></p>
 *
 * <h2>与 RiskGate 的区别</h2>
 * <ul>
 *   <li>{@link AuthorizationContext} = pre-LLM, 控制"LLM 看到哪些 tool" (过滤 callback 数组)</li>
 *   <li>{@link RiskGate} = runtime, 控制"tool 能否真执行" (反射调用前校验)</li>
 *   <li>两者并存, 互不替代: Authorizer 防 LLM "看到"不该看到的 tool;
 *       RiskGate 防"LLM 即使知道 tool, 也不能绕过规则执行"</li>
 * </ul>
 *
 * <h2>不可变性</h2>
 * <p>本 record 不可变, 一旦创建即代表"本次请求的授权视图"快照。
 * 业务侧按需用 {@code awaitingConfirmation} / {@code confirmed} / {@code permissive} 工厂。</p>
 */
public record AuthorizationContext(
        AgentIdentity identity,
        String sessionId,
        Set<String> allowedTools,    // 本次请求允许的工具子集; null/empty = 不强制白名单
        RiskLevel maxRiskLevel,      // 本次请求最高允许的风险级
        boolean requiresConfirmation // 是否在"等待用户确认"阶段 (true → 只 L1/L2, false → 全开)
) {

    /** 无限制 ctx — 让 LLM 看到所有已注册 tool (L1-L3, 不含 L4)。用于无 ctx 退化路径。 */
    public static AuthorizationContext permissive() {
        return new AuthorizationContext(null, null, null, RiskLevel.L3_BUSINESS_STATE, false);
    }

    /** 等待用户确认阶段 ctx — 仅 L1 + L2。 */
    public static AuthorizationContext awaitingConfirmation(AgentIdentity identity) {
        return new AuthorizationContext(identity, null, null, null, true);
    }

    /** 用户已确认阶段 ctx — L1-L3 全开。 */
    public static AuthorizationContext confirmed(AgentIdentity identity) {
        return new AuthorizationContext(identity, null, null, null, false);
    }
}