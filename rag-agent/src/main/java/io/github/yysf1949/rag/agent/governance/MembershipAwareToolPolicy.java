package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.builtin.port.MemberProfileRepositoryPort;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 会员等级感知的工具选择策略 — 根据会员等级过滤工具。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li>GOLD / PLATINUM 会员: 全部工具可用</li>
 *   <li>NORMAL 会员: L3_BUSINESS_STATE 工具不可用 (需要 confirmationToken 的高风险操作)</li>
 *   <li>未注册用户 (null tier): 仅 L1_READ 只读工具</li>
 * </ul>
 */
@Component
public class MembershipAwareToolPolicy implements ToolSelectionPolicy {

    private final MemberProfileRepositoryPort memberProfileRepository;
    private final ToolRegistry registry;

    public MembershipAwareToolPolicy(MemberProfileRepositoryPort memberProfileRepository,
                                     ToolRegistry registry) {
        this.memberProfileRepository = Objects.requireNonNull(memberProfileRepository);
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public List<String> filterTools(ToolSelectionContext ctx, List<String> candidateTools) {
        if (ctx == null || candidateTools == null) return List.of();

        String tier = resolveTier(ctx);

        // GOLD / PLATINUM → 全部通过
        if ("GOLD".equals(tier) || "PLATINUM".equals(tier)) {
            return List.copyOf(candidateTools);
        }

        // NORMAL → 排除 L3_BUSINESS_STATE (需要 confirmationToken 的高风险写操作)
        if ("NORMAL".equals(tier)) {
            return candidateTools.stream()
                    .filter(name -> {
                        RiskLevel risk = toolRiskLevel(name);
                        return risk != RiskLevel.L3_BUSINESS_STATE;
                    })
                    .toList();
        }

        // 未注册用户 (null) → 只有 L1_READ 只读工具
        return candidateTools.stream()
                .filter(name -> toolRiskLevel(name) == RiskLevel.L1_READ)
                .toList();
    }

    private String resolveTier(ToolSelectionContext ctx) {
        if (ctx.membershipTier() != null) {
            return ctx.membershipTier();
        }
        // 尝试从仓库查询
        if (ctx.tenantId() != null && ctx.userId() != null) {
            return memberProfileRepository
                    .findByTenantAndUser(ctx.tenantId(), ctx.userId())
                    .map(MemberProfileRepositoryPort.MemberProfile::tier)
                    .orElse(null);
        }
        return null;
    }

    private RiskLevel toolRiskLevel(String toolName) {
        try {
            return registry.get(toolName).riskLevel();
        } catch (ToolNotFoundException e) {
            return RiskLevel.L4_HIGH_RISK;
        }
    }
}
