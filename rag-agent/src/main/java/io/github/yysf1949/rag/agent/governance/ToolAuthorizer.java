package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;

import java.util.List;
import java.util.Set;

/**
 * 工具授权器接口 — 根据 {@link AuthorizationContext} 过滤工具列表。
 *
 * <h2>对齐「路条编程」文章 §"渐进式工具授权"</h2>
 * <p>文章示例: 用户"询问规则" → 只暴露 L1 工具; 用户"明确动作" → 加上 L2;
 * 用户"再次确认" → 才提供 L3。Authorizer 接口对应这一动态过程。</p>
 */
public interface ToolAuthorizer {

    /**
     * 根据 ctx 过滤 allTools, 返回本次请求允许暴露给 LLM 的 tool 列表。
     *
     * @param ctx      本次请求的授权上下文
     * @param allTools Registry 中的全部工具名
     * @return 允许暴露的工具名子集 (顺序保持)
     */
    List<String> authorizedTools(AuthorizationContext ctx, List<String> allTools);

    /**
     * 单个工具是否允许在当前上下文执行 — Runtime 校验 (RiskGate 之外)。
     * 主要供编排层 (DefaultAgentLoop) 反射调用前再校验一次。
     */
    boolean isAuthorized(String toolName, AuthorizationContext ctx);

    /** 风险级小于等于 maxRiskLevel 即认为通过。 */
    static boolean riskLevelAllowed(RiskLevel toolRisk, RiskLevel max) {
        if (toolRisk == null || max == null) return false;
        return toolRisk.compareTo(max) <= 0;
    }

    /** 白名单 null/empty = 不强制; 否则仅允许白名单内。 */
    static boolean inWhitelist(String toolName, Set<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) return true;
        return allowedTools.contains(toolName);
    }
}