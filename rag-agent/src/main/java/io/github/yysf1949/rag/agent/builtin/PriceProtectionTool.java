package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.PriceProtectionPort;
import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import org.springframework.stereotype.Component;

/**
 * 价保工具 — L1 查询价保政策/资格 + L3 申请价保退差价。
 *
 * <h2>场景</h2>
 * <ul>
 *   <li>{@code query_price_protection_policy} — "这个商品有价保吗？"（L1 只读）</li>
 *   <li>{@code check_price_protection_eligibility} — "我这个订单还能申请价保吗？"（L1 只读）</li>
 *   <li>{@code apply_price_protection} — "刚买就降价了，申请退差价"（L3 写，200 元门控）</li>
 * </ul>
 */
@Component
public class PriceProtectionTool {

    /** 价保单笔差价退赔上限（分）— 200 元 */
    public static final long MAX_REFUND_AMOUNT_CENTS = 200_00L;

    private final PriceProtectionPort repo;

    public PriceProtectionTool(PriceProtectionPort repo) {
        this.repo = repo;
    }

    // ==================== L1 查询 ====================

    @ToolSpec(
            name = "query_price_protection_policy",
            description = "查询某品类价保政策，返回protectionDays/maxRefundRatio。适用于：用户问'这个商品有价保吗'、'价保几天'。只读工具。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public QueryPolicyResponse queryPolicy(QueryPolicyRequest req) {
        var policy = repo.getPolicy(req.productCategory());
        return new QueryPolicyResponse(policy.protectionDays(), policy.maxRefundRatio());
    }

    @ToolSpec(
            name = "check_price_protection_eligibility",
            description = "检查订单价保资格，返回orderId/eligible/message。适用于：用户问'还能申请价保吗'、'买完就降价了能退差价吗'。只读工具。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public EligibilityResponse checkEligibility(EligibilityRequest req) {
        var eligible = repo.isWithinProtectionPeriod(req.orderTime(), req.productCategory());
        return new EligibilityResponse(
                req.orderId(), eligible,
                eligible ? "在价保期内，可申请价保" : "已超过价保期限");
    }

    // ==================== L3 写操作 ====================

    @ToolSpec(
            name = "apply_price_protection",
            description = "申请价保退差价，返回claimId/status/refundAmountCents。≤200元自动处理，超过转人工。用户说'刚买就降价了申请退差价'。幂等。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = true,
            requiresIdempotencyKey = true,
            maxAmountCents = 200_00L,  // 200 元上限
            requiresConfirmationToken = true
    )
    public ApplyResponse applyPriceProtection(IdempotencyKey idempotencyKey, ApplyRequest req) {
        // 0. 幂等检查：同一 idempotencyKey 已存在的申请直接返回
        var existing = repo.findByIdempotencyKey(req.idempotencyKey(), req.tenantId());
        if (existing.isPresent()) {
            var r = existing.get();
            return new ApplyResponse(r.claimId(), r.status(), r.refundAmountCents());
        }

        // 1. 金额门控
        if (req.refundAmountCents() > MAX_REFUND_AMOUNT_CENTS) {
            throw new AmountLimitExceededException(
                    "apply_price_protection", req.refundAmountCents(), MAX_REFUND_AMOUNT_CENTS);
        }

        // 2. 查价保政策
        var policy = repo.getPolicy(req.productCategory());

        // 3. 价保比例检查
        long maxRefund = (long) (req.originalPriceCents() * policy.maxRefundRatio());
        if (req.refundAmountCents() > maxRefund) {
            throw new IllegalArgumentException(
                    "Refund amount exceeds policy max: " + req.refundAmountCents()
                            + " > " + maxRefund);
        }

        // 4. 检查价保期
        if (!repo.isWithinProtectionPeriod(req.orderTime(), req.productCategory())) {
            throw new IllegalStateException("Order is outside the price protection period");
        }

        // 5. 保存申请
        var claim = PriceProtectionPort.PriceProtectionRecord.pending(
                repo.nextClaimId(), req.tenantId(), req.userId(),
                req.orderId(), req.productId(), req.refundAmountCents(),
                req.originalPriceCents(), req.currentPriceCents(), req.reason(), req.idempotencyKey());
        repo.save(claim);
        return new ApplyResponse(claim.claimId(), claim.status(), claim.refundAmountCents());
    }

    // ==================== Records ====================

    public record QueryPolicyRequest(String tenantId, String userId, String productCategory) { }
    public record QueryPolicyResponse(int protectionDays, double maxRefundRatio) { }

    public record EligibilityRequest(String tenantId, String userId, String orderId,
                                     String orderTime, String productCategory) { }
    public record EligibilityResponse(String orderId, boolean eligible, String message) { }

    public record ApplyRequest(String tenantId, String userId, String orderId,
                               String productId, long refundAmountCents,
                               long originalPriceCents, long currentPriceCents,
                               String orderTime, String productCategory,
                               String reason, String idempotencyKey) { }
    public record ApplyResponse(String claimId, String status, long refundAmountCents) { }
}