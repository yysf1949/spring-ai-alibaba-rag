package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 优惠券工具 — L3 补发 + L1 查询。
 */
@Component
public class CouponTool {

    /** 单张优惠券金额上限（分）— 200 元 */
    public static final long ISSUE_MAX_AMOUNT_CENTS = 200_00L;

    private final CouponRepositoryPort repo;
    private final IdempotencyStore idempotencyStore;

    public CouponTool(CouponRepositoryPort repo, IdempotencyStore idempotencyStore) {
        this.repo = repo;
        this.idempotencyStore = idempotencyStore;
    }

    @ToolSpec(
            name = "issue_coupon",
            description = "补发优惠券，单张≤200元可自动执行，>200元需转人工审批。"
                    + "适用场景：售后补偿、活动奖励、客户挽留。调用方必须传 idempotencyKey 防重复发放。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = true,
            requiresIdempotencyKey = true,
            maxAmountCents = 200_00L,  // 200 元上限
            requiresConfirmationToken = true
    )
    public IssueCouponResponse issueCoupon(IdempotencyKey idempotencyKey, IssueCouponRequest req) {
        // 幂等检查 — 文章: '即使模型重复调用，系统也应该返回第一次的执行结果'
        IdempotencyStore.PutResult put = idempotencyStore.putIfAbsent(idempotencyKey, null);
        if (put.isReplay()) {
            String existingId = (String) put.value();
            if (existingId != null) {
                return new IssueCouponResponse(existingId, req.amountCents(), "ACTIVE");
            }
        }

        if (req.amountCents() > ISSUE_MAX_AMOUNT_CENTS) {
            throw new io.github.yysf1949.rag.agent.exception.AmountLimitExceededException(
                    "issue_coupon", req.amountCents(), ISSUE_MAX_AMOUNT_CENTS);
        }
        var coupon = new CouponRepositoryPort.CouponRecord(
                CouponRepositoryPort.newCouponId(),
                req.tenantId(), req.userId(), req.orderId(),
                req.amountCents(), req.reasonTag(), "ACTIVE");
        repo.save(coupon);
        // 回填幂等结果
        idempotencyStore.replace(idempotencyKey, coupon.couponId());
        return new IssueCouponResponse(coupon.couponId(), coupon.amountCents(), coupon.status());
    }

    @ToolSpec(
            name = "list_active_coupons",
            description = "查询用户当前可用的优惠券列表。"
                    + "适用于：用户问'我有什么优惠券'、'有没有可用的券'。只读工具，返回优惠券ID、金额、来源标签。",
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
