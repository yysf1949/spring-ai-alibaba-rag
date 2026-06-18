package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryCouponRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 优惠券工具 — L3 补发 + L1 查询。
 */
@Component
public class CouponTool {

    /** 单张优惠券金额上限（分）— 200 元 */
    public static final long ISSUE_MAX_AMOUNT_CENTS = 200_00L;

    private final InMemoryCouponRepository repo;

    public CouponTool(InMemoryCouponRepository repo) {
        this.repo = repo;
    }

    @ToolSpec(
            name = "issue_coupon",
            description = "补发优惠券（单张超过 200 元需转人工审批）。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = false,
            requiresIdempotencyKey = true,
            maxAmountCents = 200_00L  // 200 元上限
    )
    public IssueCouponResponse issueCoupon(IssueCouponRequest req) {
        if (req.amountCents() > ISSUE_MAX_AMOUNT_CENTS) {
            throw new io.github.yysf1949.rag.agent.exception.AmountLimitExceededException(
                    "issue_coupon", req.amountCents(), ISSUE_MAX_AMOUNT_CENTS);
        }
        var coupon = new CouponRepositoryPort.CouponRecord(
                CouponRepositoryPort.newCouponId(),
                req.tenantId(), req.userId(), req.orderId(),
                req.amountCents(), req.reasonTag(), "ACTIVE");
        repo.save(coupon);
        return new IssueCouponResponse(coupon.couponId(), coupon.amountCents(), coupon.status());
    }

    @ToolSpec(
            name = "list_active_coupons",
            description = "查询用户当前有效优惠券列表（只读）。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public ListCouponsResponse listActiveCoupons(ListCouponsRequest req) {
        var coupons = repo.findActiveByTenantAndUser(req.tenantId(), req.userId());
        var list = coupons.stream()
                .map(c -> new CouponView(c.couponId(), c.amountCents(), c.reasonTag()))
                .toList();
        return new ListCouponsResponse(list);
    }

    public record IssueCouponRequest(String tenantId, String userId, String orderId,
                                     long amountCents, String reasonTag) { }
    public record IssueCouponResponse(String couponId, long amountCents, String status) { }
    public record ListCouponsRequest(String tenantId, String userId) { }
    public record ListCouponsResponse(List<CouponView> coupons) { }
    public record CouponView(String couponId, long amountCents, String reasonTag) { }
}
