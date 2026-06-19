package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.store.InMemoryCouponRepository;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponToolTest {

    private InMemoryCouponRepository repo;
    private InMemoryIdempotencyStore idemStore;
    private CouponTool tool;

    @BeforeEach
    void setUp() {
        repo = new InMemoryCouponRepository();
        idemStore = new InMemoryIdempotencyStore();
        tool = new CouponTool(repo, idemStore);
    }

    private IdempotencyKey key(String token) {
        return IdempotencyKey.of("tenant-1", "user-1", "s1", "issue_coupon", token);
    }

    @Test
    void issueCouponHappyPath() {
        var resp = tool.issueCoupon(key("coupon-1"),
                new CouponTool.IssueCouponRequest(
                        "tenant-1", "user-1", "ORD-1", 20_00L, "WELCOME_BACK"));
        assertThat(resp.couponId()).startsWith("CPN-");
        assertThat(resp.amountCents()).isEqualTo(20_00L);
    }

    @Test
    void issueCouponOverLimitThrowsHandoff() {
        // 1000 元超过 200 元上限
        assertThatThrownBy(() ->
                tool.issueCoupon(key("coupon-over"),
                        new CouponTool.IssueCouponRequest(
                                "tenant-1", "user-1", "ORD-1", 1000_00L, "BIG_REWARD")))
                .isInstanceOf(io.github.yysf1949.rag.agent.exception.AmountLimitExceededException.class);
    }

    @Test
    void listActiveCoupons() {
        tool.issueCoupon(key("coupon-a"),
                new CouponTool.IssueCouponRequest(
                        "tenant-1", "user-1", "ORD-1", 20_00L, "W1"));
        tool.issueCoupon(key("coupon-b"),
                new CouponTool.IssueCouponRequest(
                        "tenant-1", "user-1", "ORD-2", 30_00L, "W2"));
        var resp = tool.listActiveCoupons(new CouponTool.ListCouponsRequest(
                "tenant-1", "user-1"));
        assertThat(resp.coupons()).hasSize(2);
    }
}
