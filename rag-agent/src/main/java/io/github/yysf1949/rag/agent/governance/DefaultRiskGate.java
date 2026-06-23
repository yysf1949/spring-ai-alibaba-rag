package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 默认风险门控 — 实现文章 4 级规则。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li><b>L1</b> — 全部放行</li>
 *   <li><b>L2</b> — 工具声明 {@code requiresIdempotencyKey=true} 时，调用方必须传</li>
 *   <li><b>L3</b> — 必须传 idempotencyKey（写操作兜底）</li>
 *   <li><b>L4</b> — 必须传 idempotencyKey + 调用者必须有 {@code admin} 角色</li>
 * </ul>
 */
@Component
public class DefaultRiskGate implements RiskGate {

    private static final Set<String> ADMIN_ROLES = Set.of("admin", "system");

    private final ConfirmationService confirmationService;

    public DefaultRiskGate(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @Override
    public void check(ToolDescriptor descriptor, AgentIdentity identity,
                      IdempotencyKey idemKey, Long requestedAmountCents) {
        RiskLevel level = descriptor.riskLevel();
        boolean hasAdminRole = identity.roles().stream().anyMatch(ADMIN_ROLES::contains);

        if (level == RiskLevel.L1_READ) {
            return; // 全部放行
        }

        // L2+ 必须有幂等键
        if (idemKey == null) {
            throw new ToolRiskDeniedException(String.format(
                    "Tool [%s] is %s; idempotencyKey is required", descriptor.name(), level));
        }

        // L4 额外要求 admin 角色
        if (level == RiskLevel.L4_HIGH_RISK && !hasAdminRole) {
            throw new ToolRiskDeniedException(String.format(
                    "Tool [%s] is L4_HIGH_RISK; caller must have admin role, got %s",
                    descriptor.name(), identity.roles()));
        }

        // 工具声明 requiresIdempotencyKey 但调用方没传（idemKey 仍可能是 null 之前已拒；这里再校验 token 不为空）
        if (descriptor.requiresIdempotencyKey() && (idemKey.rawToken() == null || idemKey.rawToken().isBlank())) {
            throw new ToolRiskDeniedException(String.format(
                    "Tool [%s] requires idempotencyKey with non-blank token", descriptor.name()));
        }

        // Phase 10: L3 金额门控 — 超过 maxAmountCents 抛 AmountLimitExceededException
        if (level == RiskLevel.L3_BUSINESS_STATE
                && descriptor.maxAmountCents() != null
                && descriptor.maxAmountCents() >= 0
                && requestedAmountCents != null
                && requestedAmountCents > descriptor.maxAmountCents()) {
            throw new AmountLimitExceededException(
                    descriptor.name(), requestedAmountCents, descriptor.maxAmountCents());
        }

        // Phase 21: 确认令牌校验 — requiresConfirmationToken=true 的 L3 工具需要有效 token
        if (descriptor.requiresConfirmationToken()) {
            // ConfirmationToken 通过 identity 的 confirmationToken 字段传入
            // 这里检查是否存在有效的待消费 token
            String tokenRaw = identity.confirmationToken();
            if (tokenRaw == null || tokenRaw.isBlank()) {
                throw new ToolRiskDeniedException(String.format(
                        "Tool [%s] requires a confirmationToken; user must explicitly confirm before execution",
                        descriptor.name()));
            }
            ConfirmationToken token = confirmationService.validateAndConsume(
                    tokenRaw, descriptor.name(), identity.userId());
            if (token == null) {
                throw new ToolRiskDeniedException(String.format(
                        "Tool [%s] confirmationToken is invalid, expired, or already consumed",
                        descriptor.name()));
            }
        }
    }
}