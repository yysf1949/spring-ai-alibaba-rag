package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import io.github.yysf1949.rag.agent.exception.HandoffRequiredException;
import org.springframework.stereotype.Component;

/**
 * 退款工具 — L3 创建申请 + L4 直接退款。
 *
 * <h2>4 级风险对照</h2>
 * <ul>
 *   <li>{@code create_refund} — L3_BUSINESS_STATE（写业务态，单笔 ≤ 500 元不需转人工）</li>
 *   <li>{@code approve_refund} — L4_HIGH_RISK（直接打款，admin 角色强制）</li>
 * </ul>
 *
 * <h2>Phase 13b M5: 业务规则前置校验</h2>
 * <p>{@code create_refund} 在写 Repo 前必须先调 {@link RefundRuleTool#checkRefundRules}，
 * 命中"组合优惠 / 退款期 / 支付渠道"任一规则 → 抛 {@link HandoffRequiredException}，
 * 由 {@code DefaultAgentLoop} 捕获并自动转人工（带完整 matchedRules + riskNote）。</p>
 */
@Component
public class RefundTool {

    /** 创建退款单笔金额上限（分）— 500 元 */
    public static final long CREATE_MAX_AMOUNT_CENTS = 500_00L;

    private final RefundRepositoryPort repo;
    private final RefundRuleTool ruleTool;

    public RefundTool(RefundRepositoryPort repo, RefundRuleTool ruleTool) {
        this.repo = repo;
        this.ruleTool = ruleTool;
    }

    @ToolSpec(
            name = "create_refund",
            description = "创建退款申请（待审批），单笔超过 500 元需转人工。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = false,
            requiresIdempotencyKey = true,
            maxAmountCents = 500_00L,  // 500 元上限
            requiresConfirmationToken = true  // Phase 21: 文章要求"用户明确确认"
    )
    public CreateRefundResponse createRefund(CreateRefundRequest req) {
        // 金额门控 — RiskGate 也会检查，这里写一次保证直接调用时也走门控
        if (req.amountCents() > CREATE_MAX_AMOUNT_CENTS) {
            throw new io.github.yysf1949.rag.agent.exception.AmountLimitExceededException(
                    "create_refund", req.amountCents(), CREATE_MAX_AMOUNT_CENTS);
        }
        // Phase 13b M5: 业务规则前置 — 退款期 / 组合优惠 / 支付渠道
        RefundRuleTool.RefundRuleResult rule = ruleTool.checkRefundRules(
                new RefundRuleTool.CheckRefundRulesRequest(req.orderId()));
        if (rule.requiresManual()) {
            throw new HandoffRequiredException(
                    "create_refund",
                    rule.reason(),
                    rule.matchedRules(),
                    "Order [" + req.orderId() + "] hits business rule " + rule.reason()
                            + "; refund requires manual review per company policy.");
        }
        var refund = new RefundRepositoryPort.RefundRecord(
                RefundRepositoryPort.newRefundId(),
                req.tenantId(), req.userId(), req.orderId(),
                req.amountCents(), req.reason(), "PENDING");
        repo.save(refund);
        return new CreateRefundResponse(refund.refundId(), "PENDING", refund.amountCents());
    }

    @ToolSpec(
            name = "approve_refund",
            description = "审批通过退款申请（直接打款 — L4 高风险，仅 admin 角色可执行）。",
            riskLevel = RiskLevel.L4_HIGH_RISK,
            idempotent = true,  // 同 refundId + admin 重复审批 = 幂等
            requiresIdempotencyKey = true
    )
    public ApproveRefundResponse approveRefund(ApproveRefundRequest req) {
        var existing = repo.findByIdAndTenant(req.refundId(), req.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Refund not found"));
        // 幂等：已 APPROVED 直接返回
        if ("APPROVED".equals(existing.status())) {
            return new ApproveRefundResponse(existing.refundId(), "APPROVED", existing.amountCents());
        }
        var approved = new RefundRepositoryPort.RefundRecord(
                existing.refundId(), existing.tenantId(), existing.userId(),
                existing.orderId(), existing.amountCents(), existing.reason(), "APPROVED");
        repo.save(approved);
        return new ApproveRefundResponse(approved.refundId(), "APPROVED", approved.amountCents());
    }

    public record CreateRefundRequest(String tenantId, String userId, String orderId,
                                      long amountCents, String reason) { }
    public record CreateRefundResponse(String refundId, String status, long amountCents) { }
    public record ApproveRefundRequest(String tenantId, String adminUserId, String refundId,
                                       long amountCents) { }
    public record ApproveRefundResponse(String refundId, String status, long amountCents) { }
}