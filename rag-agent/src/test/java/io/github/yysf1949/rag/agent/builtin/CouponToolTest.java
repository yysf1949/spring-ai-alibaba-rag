package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.store.InMemoryCouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponToolTest {

    private InMemoryCouponRepository repo;
    private CouponTool tool;

    @BeforeEach
    void setUp() {
        repo = new InMemoryCouponRepository();
        tool = new CouponTool(repo);
    }

    @Test
    void issueCouponHappyPath() {
        var resp = tool.issueCoupon(new CouponTool.IssueCouponRequest(
                "tenant-1", "user-1", "ORD-1", 20_00L, "WELCOME_BACK"));
        assertThat(resp.couponId()).startsWith("CPN-");
        assertThat(resp.amountCents()).isEqualTo(20_00L);
    }

    @Test
    void issueCouponOverLimitThrowsHandoff() {
        // 1000 元超过 200 元上限
        assertThatThrownBy(() ->
                tool.issueCoupon(new CouponTool.IssueCouponRequest(
                        "tenant-1", "user-1", "ORD-1", 1000_00L, "BIG_REWARD")))
                .isInstanceOf(io.github.yysf1949.rag.agent.exception.AmountLimitExceededException.class);
    }

    @Test
    void listActiveCoupons() {
        tool.issueCoupon(new CouponTool.IssueCouponRequest(
                "tenant-1", "user-1", "ORD-1", 20_00L, "W1"));
        tool.issueCoupon(new CouponTool.IssueCouponRequest(
                "tenant-1", "user-1", "ORD-2", 30_00L, "W2"));
        var resp = tool.listActiveCoupons(new CouponTool.ListCouponsRequest(
                "tenant-1", "user-1"));
        assertThat(resp.coupons()).hasSize(2);
    }
}