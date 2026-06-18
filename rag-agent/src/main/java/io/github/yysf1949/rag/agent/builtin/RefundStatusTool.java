package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 退款状态查询/取消工具 — 补齐退款闭环。
 *
 * <h2>为什么需要独立于 RefundTool</h2>
 * <p>{@link RefundTool} 负责"创建"和"审批"退款（L3/L4 写操作），
 * 但用户最常问的是"我的退款到哪了？"（L1 只读）和"我不想退了"（L2 可逆）。
 * 按文章"查询与执行必须分开"原则，拆为独立工具。</p>
 *
 * <h2>风险分级</h2>
 * <ul>
 *   <li>{@code query_refund} — L1_READ（只查退款状态，不改任何数据）</li>
 *   <li>{@code cancel_refund} — L2_REVERSIBLE（取消 PENDING 状态的退款申请，
 *       已 APPROVED 的不能取消，需转人工）</li>
 * </ul>
 */
@Component
public class RefundStatusTool {

    private final RefundRepositoryPort repo;

    public RefundStatusTool(RefundRepositoryPort repo) {
        this.repo = repo;
    }

    @ToolSpec(
            name = "query_refund",
            description = "查询退款申请的状态（PENDING/APPROVED/REJECTED/CANCELLED）。只读工具。"
                    + "适用于：用户问'退款到哪了'、'退款什么时候到账'。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public QueryRefundResponse queryRefund(QueryRefundRequest req) {
        Optional<RefundRepositoryPort.RefundRecord> refund =
                repo.findByIdAndTenant(req.refundId(), req.tenantId());
        if (refund.isEmpty()) {
            return new QueryRefundResponse(req.refundId(), "NOT_FOUND", 0, null, "退款记录不存在");
        }
        var r = refund.get();
        return new QueryRefundResponse(r.refundId(), r.status(), r.amountCents(), r.reason(), null);
    }

    @ToolSpec(
            name = "cancel_refund",
            description = "取消 PENDING 状态的退款申请（已审批通过的不能取消，需转人工）。"
                    + "适用于：用户说'我不想退了'、'取消退款申请'。",
            riskLevel = RiskLevel.L2_REVERSIBLE,
            idempotent = false,
            requiresIdempotencyKey = true
    )
    public CancelRefundResponse cancelRefund(CancelRefundRequest req) {
        var existing = repo.findByIdAndTenant(req.refundId(), req.tenantId());
        if (existing.isEmpty()) {
            return new CancelRefundResponse(req.refundId(), "NOT_FOUND", "退款记录不存在");
        }
        var r = existing.get();
        if (!"PENDING".equals(r.status())) {
            return new CancelRefundResponse(r.refundId(), r.status(),
                    "退款状态为 " + r.status() + "，无法取消，请联系人工客服");
        }
        var cancelled = new RefundRepositoryPort.RefundRecord(
                r.refundId(), r.tenantId(), r.userId(),
                r.orderId(), r.amountCents(), r.reason(), "CANCELLED");
        repo.save(cancelled);
        return new CancelRefundResponse(cancelled.refundId(), "CANCELLED", "退款申请已取消");
    }

    public record QueryRefundRequest(String tenantId, String refundId) {}
    public record QueryRefundResponse(String refundId, String status, long amountCents,
                                       String reason, String error) {}
    public record CancelRefundRequest(String tenantId, String userId, String refundId) {}
    public record CancelRefundResponse(String refundId, String status, String message) {}
}
