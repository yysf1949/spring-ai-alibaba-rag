package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;

/**
 * 阶段感知工具授权器 — 根据 {@link AuthorizationContext#requiresConfirmation()}
 * 决定 LLM 在本阶段能看到哪些 tool。
 *
 * <h2>对齐「路条编程」文章 §"渐进式工具授权" 3 阶段</h2>
 * <ul>
 *   <li>{@code requiresConfirmation = true} (询问/确认阶段) → 仅 L1_READ + L2_REVERSIBLE
 *       (Agent 只能"读 + 写草稿", 不能直接改业务态)</li>
 *   <li>{@code requiresConfirmation = false} (已确认阶段) → 全 L1-L3 工具开放
 *       (L4 HIGH_RISK 仍走人工审批, 工具列表里不暴露, 让人工触发)</li>
 * </ul>
 *
 * <h2>与白名单叠加</h2>
 * <p>ctx.allowedTools 显式指定 → 取交集 (白名单 ∩ 阶段允许)。
 * 例如: 客服后台手工只暴露"退款类工具"给某个客服角色。</p>
 */
@Component
public class StageAwareToolAuthorizer implements ToolAuthorizer {

    /** "等待确认"阶段最高允许风险级 — 防止 LLM 在用户未确认时直接 cancel_order */
    public static final RiskLevel AWAITING_CONFIRMATION_MAX_RISK = RiskLevel.L2_REVERSIBLE;

    /** "已确认"阶段最高允许风险级 — L3 业务态可执行, L4 仍走人工 */
    public static final RiskLevel CONFIRMED_MAX_RISK = RiskLevel.L3_BUSINESS_STATE;

    private final ToolRegistry registry;
    private final List<ToolSelectionPolicy> policies;

    public StageAwareToolAuthorizer(ToolRegistry registry) {
        this(registry, List.of());
    }

    @Autowired(required = false)
    public StageAwareToolAuthorizer(ToolRegistry registry, List<ToolSelectionPolicy> policies) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policies = policies != null ? policies : List.of();
    }

    @Override
    public List<String> authorizedTools(AuthorizationContext ctx, List<String> allTools) {
        return authorizedTools(ctx, allTools, null);
    }

    /**
     * 增强版授权过滤 — 先按 riskLevel 过滤, 再遍历所有 {@link ToolSelectionPolicy} 进一步过滤。
     *
     * @param ctx               授权上下文
     * @param allTools          全部工具名
     * @param selectionContext  工具选择上下文 (null = 跳过 policy 过滤, 保持向后兼容)
     * @return 最终允许的工具名子集
     */
    public List<String> authorizedTools(AuthorizationContext ctx, List<String> allTools,
                                        ToolSelectionPolicy.ToolSelectionContext selectionContext) {
        if (ctx == null || allTools == null) return List.of();
        RiskLevel effectiveMax = ctx.maxRiskLevel() != null
                ? ctx.maxRiskLevel()
                : (ctx.requiresConfirmation() ? AWAITING_CONFIRMATION_MAX_RISK : CONFIRMED_MAX_RISK);
        List<String> filtered = allTools.stream()
                .filter(name -> isAuthorized(name, ctx))
                .filter(name -> {
                    // 再叠一层 effectiveMax 校验 (覆盖 ctx.maxRiskLevel 为 null 的退化路径)
                    return ToolAuthorizer.riskLevelAllowed(toolRiskLevel(name), effectiveMax);
                })
                .toList();

        // 没有 policy 或没有 selectionContext → 跳过, 保持向后兼容
        if (policies.isEmpty() || selectionContext == null) {
            return filtered;
        }

        // 遍历所有 policy 进一步过滤 (取交集)
        List<String> result = filtered;
        for (ToolSelectionPolicy policy : policies) {
            result = policy.filterTools(selectionContext, result);
        }
        return result;
    }

    @Override
    public boolean isAuthorized(String toolName, AuthorizationContext ctx) {
        if (toolName == null || ctx == null) return false;
        // 1. 工具必须在 registry 中存在
        RiskLevel risk = toolRiskLevel(toolName);
        // 2. 风险级 ≤ ctx.maxRiskLevel (ctx 显式指定时优先)
        if (ctx.maxRiskLevel() != null) {
            if (!ToolAuthorizer.riskLevelAllowed(risk, ctx.maxRiskLevel())) return false;
        } else {
            // 3. 否则按阶段默认
            RiskLevel defaultMax = ctx.requiresConfirmation()
                    ? AWAITING_CONFIRMATION_MAX_RISK
                    : CONFIRMED_MAX_RISK;
            if (!ToolAuthorizer.riskLevelAllowed(risk, defaultMax)) return false;
        }
        // 4. 白名单叠加
        return ToolAuthorizer.inWhitelist(toolName, ctx.allowedTools());
    }

    private RiskLevel toolRiskLevel(String toolName) {
        try {
            return registry.get(toolName).riskLevel();
        } catch (ToolNotFoundException e) {
            // 工具不存在 → 视为最高风险拒绝
            return RiskLevel.L4_HIGH_RISK;
        }
    }
}