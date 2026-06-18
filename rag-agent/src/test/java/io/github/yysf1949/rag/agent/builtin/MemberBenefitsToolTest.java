package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.MemberBenefitsTool;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryMemberProfileRepository;
import io.github.yysf1949.rag.agent.builtin.port.MemberProfileRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会员权益查询工具测试 — 4 个用例对齐文章 "查询会员权益" 能力。
 */
class MemberBenefitsToolTest {

    private InMemoryMemberProfileRepository repo;
    private MemberBenefitsTool tool;

    @BeforeEach
    void setUp() {
        repo = new InMemoryMemberProfileRepository();
        tool = new MemberBenefitsTool(repo);
    }

    @Test
    void normalMemberReturnsNoDiscount() {
        repo.save(new MemberProfileRepositoryPort.MemberProfile(
                "u1", "t1", "NORMAL", 100L, List.of("free_return_shipping")));

        var resp = tool.query(new MemberBenefitsTool.GetMemberBenefitsRequest("t1", "u1"));

        assertThat(resp.tier()).isEqualTo("NORMAL");
        assertThat(resp.pointsBalance()).isEqualTo(100L);
        assertThat(resp.perks()).containsExactly("free_return_shipping");
        assertThat(resp.couponDiscountCents()).isEqualTo(0L);
    }

    @Test
    void goldMemberReturns10YuanDiscount() {
        repo.save(new MemberProfileRepositoryPort.MemberProfile(
                "u2", "t1", "GOLD", 5000L, List.of("free_return_shipping", "priority_support")));

        var resp = tool.query(new MemberBenefitsTool.GetMemberBenefitsRequest("t1", "u2"));

        assertThat(resp.tier()).isEqualTo("GOLD");
        assertThat(resp.couponDiscountCents()).isEqualTo(10_00L);
        assertThat(resp.perks()).hasSize(2);
    }

    @Test
    void platinumMemberReturns20YuanDiscount() {
        repo.save(new MemberProfileRepositoryPort.MemberProfile(
                "u3", "t1", "PLATINUM", 50_000L, List.of("vip_line", "exclusive_coupon")));

        var resp = tool.query(new MemberBenefitsTool.GetMemberBenefitsRequest("t1", "u3"));

        assertThat(resp.tier()).isEqualTo("PLATINUM");
        assertThat(resp.couponDiscountCents()).isEqualTo(20_00L);
    }

    @Test
    void unknownUserReturnsNormalDefault() {
        // repo 为空, findByTenantAndUser 走 orElse → NORMAL 默认
        var resp = tool.query(new MemberBenefitsTool.GetMemberBenefitsRequest("t1", "ghost-user"));

        assertThat(resp.tier()).isEqualTo("NORMAL");
        assertThat(resp.pointsBalance()).isEqualTo(0L);
        assertThat(resp.couponDiscountCents()).isEqualTo(0L);
        assertThat(resp.perks()).isEmpty();
    }
}