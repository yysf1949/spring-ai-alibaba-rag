package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.service.CouponApplicationService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 优惠券工具 — L3 补发 + L1 查询。
 *
 * <h2>委托模式</h2>
 * <p>本工具类仅负责 API 契约（参数/返回值），所有业务逻辑委托给
 * {@link CouponApplicationService}。Agent 和管理后台共享同一个领域服务，
 * 确保「不应该有一套给 Agent 用的简化逻辑，另一套给管理后台用的完整逻辑」。</p>
 */
@Component
public class CouponTool {

    /** 单张优惠券金额上限（分）— 200 元 */
    public static final long ISSUE_MAX_AMOUNT_CENTS = 200_00L;

    private final CouponApplicationService couponApplicationService;

    public CouponTool(CouponApplicationService couponApplicationService) {
        this.couponApplicationService = couponApplicationService;
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
        // 委托给领域服务 — Agent 和管理后台走同一条代码路径
        var record = couponApplicationService.issueCoupon(
                req.tenantId(), req.userId(), req.orderId(),
                req.amountCents(), req.reasonTag(), idempotencyKey);
        return new IssueCouponResponse(record.couponId(), record.amountCents(), record.status());
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
        // 委托给领域服务
        var coupons = couponApplicationService.listActiveCoupons(req.tenantId(), req.userId());
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
