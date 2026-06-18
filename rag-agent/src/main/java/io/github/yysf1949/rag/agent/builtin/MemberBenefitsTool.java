package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.MemberProfileRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 会员权益查询工具 — L1 只读, 让 Agent 知道"用户是什么级别 + 享受什么折扣"。
 *
 * <h2>对齐「路条编程」文章 §2 能力清单 "查询会员权益"</h2>
 * <p>原话: 客服系统需要查询用户身份 / 会员等级 / 积分余额 / 可用特权。
 * AI Agent 在执行 refund / coupon / 售后时, 应先调此工具
 * 了解用户身份后再决定动作 (例如: GOLD 会员专属 10 元补偿券)。</p>
 *
 * <h2>3 级会员 (Phase 14 mock)</h2>
 * <ul>
 *   <li>NORMAL — 0 折扣</li>
 *   <li>GOLD — 10 元 (1000 cents) 折扣</li>
 *   <li>PLATINUM — 20 元 (2000 cents) 折扣</li>
 * </ul>
 */
@Component
public class MemberBenefitsTool {

    public static final long GOLD_DISCOUNT_CENTS = 10_00L;
    public static final long PLATINUM_DISCOUNT_CENTS = 20_00L;
    public static final long NORMAL_DISCOUNT_CENTS = 0L;

    private final MemberProfileRepositoryPort repo;

    public MemberBenefitsTool(MemberProfileRepositoryPort repo) {
        this.repo = Objects.requireNonNull(repo, "repo");
    }

    @ToolSpec(
            name = "get_member_benefits",
            description = "查询用户会员等级 + 积分余额 + 可用特权 + 等级折扣。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public MemberBenefitsResponse query(GetMemberBenefitsRequest req) {
        Objects.requireNonNull(req, "req");

        var profile = repo.findByTenantAndUser(req.tenantId(), req.userId())
                .orElse(new MemberProfileRepositoryPort.MemberProfile(
                        req.userId(), req.tenantId(), "NORMAL", 0L, List.of()));

        long discount = discountForTier(profile.tier());

        return new MemberBenefitsResponse(
                profile.userId(),
                profile.tier(),
                profile.pointsBalance(),
                profile.perks(),
                discount);
    }

    private static long discountForTier(String tier) {
        if (tier == null) return NORMAL_DISCOUNT_CENTS;
        return switch (tier) {
            case "GOLD" -> GOLD_DISCOUNT_CENTS;
            case "PLATINUM" -> PLATINUM_DISCOUNT_CENTS;
            default -> NORMAL_DISCOUNT_CENTS;
        };
    }

    public record GetMemberBenefitsRequest(String tenantId, String userId) { }

    public record MemberBenefitsResponse(
            String userId,
            String tier,                 // NORMAL / GOLD / PLATINUM
            long pointsBalance,
            List<String> perks,
            long couponDiscountCents     // 等级折扣
    ) { }
}