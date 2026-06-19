package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.service.RefundApplicationService;
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
 * <h2>委托模式</h2>
 * <p>本工具类仅负责 API 契约（参数/返回值），所有业务逻辑委托给
 * {@link RefundApplicationService}。Agent 和管理后台共享同一个领域服务，
 * 确保「不应该有一套给 Agent 用的简化逻辑，另一套给管理后台用的完整逻辑」。</p>
 */
@Component
public class RefundTool {

    /** 创建退款单笔金额上限（分）— 500 元 */
    public static final long CREATE_MAX_AMOUNT_CENTS = 500_00L;

    private final RefundApplicationService refundApplicationService;

    public RefundTool(RefundApplicationService refundApplicationService) {
        this.refundApplicationService = refundApplicationService;
    }

    @ToolSpec(
            name = "create_refund",
            description = "创建退款申请，需提供确认令牌(confirmationToken)。退款金额≤500元可自动审批，>500元需人工审批。"
                    + "幂等防重复退款。触发业务规则(组合优惠/退款期/支付渠道)时自动转人工。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = true,
            requiresIdempotencyKey = true,
            maxAmountCents = 500_00L,  // 500 元上限
            requiresConfirmationToken = true  // Phase 21: 文章要求"用户明确确认"
    )
    public CreateRefundResponse createRefund(CreateRefundRequest req) {
        // 委托给领域服务 — Agent 和管理后台走同一条代码路径
        var record = refundApplicationService.createRefund(
                req.tenantId(), req.userId(), req.orderId(),
                req.amountCents(), req.reason(), null);
        return new CreateRefundResponse(record.refundId(), record.status(), record.amountCents());
    }

    @ToolSpec(
            name = "approve_refund",
            description = "审批退款申请，审批通过后直接打款。仅人工客服(admin角色)可执行，仅当退款金额>500元时才需要调用。"
                    + "幂等：同一退款单重复审批直接返回当前状态。",
            riskLevel = RiskLevel.L4_HIGH_RISK,
            idempotent = true,  // 同 refundId + admin 重复审批 = 幂等
            requiresIdempotencyKey = true
    )
    public ApproveRefundResponse approveRefund(ApproveRefundRequest req) {
        // 委托给领域服务
        var record = refundApplicationService.approveRefund(
                req.refundId(), req.tenantId(), req.adminUserId());
        return new ApproveRefundResponse(record.refundId(), record.status(), record.amountCents());
    }

    public record CreateRefundRequest(String tenantId, String userId, String orderId,
                                      long amountCents, String reason) { }
    public record CreateRefundResponse(String refundId, String status, long amountCents) { }
    public record ApproveRefundRequest(String tenantId, String adminUserId, String refundId,
                                       long amountCents) { }
    public record ApproveRefundResponse(String refundId, String status, long amountCents) { }
}
