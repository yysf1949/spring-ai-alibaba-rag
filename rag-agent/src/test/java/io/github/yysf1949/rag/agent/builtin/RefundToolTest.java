package io.github.yysf1949.rag.agent.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundToolTest {

    private RefundRepository repo;
    private RefundTool tool;

    @BeforeEach
    void setUp() {
        repo = new RefundRepository();
        tool = new RefundTool(repo);
    }

    @Test
    void createRefundApplication() {
        var resp = tool.createRefund(new RefundTool.CreateRefundRequest(
                "tenant-1", "user-1", "ORD-1", 50_00L, "商品质量问题"));
        assertThat(resp.refundId()).startsWith("REF-");
        assertThat(resp.status()).isEqualTo("PENDING");
        assertThat(resp.amountCents()).isEqualTo(50_00L);
    }

    @Test
    void createRefundOverLimitGoesToHandoff() {
        // 1000 元超过 500 元上限
        assertThatThrownBy(() ->
                tool.createRefund(new RefundTool.CreateRefundRequest(
                        "tenant-1", "user-1", "ORD-1", 1000_00L, "高额退款")))
                .isInstanceOf(io.github.yysf1949.rag.agent.exception.AmountLimitExceededException.class);
    }

    @Test
    void approveRefundTransitionsToApproved() {
        var created = tool.createRefund(new RefundTool.CreateRefundRequest(
                "tenant-1", "user-1", "ORD-1", 50_00L, "ok"));
        // L4 直接退款（admin role 由 RiskGate 校验，本测试只测业务逻辑）
        var resp = tool.approveRefund(new RefundTool.ApproveRefundRequest(
                "tenant-1", "admin-1", created.refundId(), 50_00L));
        assertThat(resp.status()).isEqualTo("APPROVED");
    }

    @Test
    void approveRefundTwiceIdempotent() {
        var created = tool.createRefund(new RefundTool.CreateRefundRequest(
                "tenant-1", "user-1", "ORD-1", 50_00L, "ok"));
        var first = tool.approveRefund(new RefundTool.ApproveRefundRequest(
                "tenant-1", "admin-1", created.refundId(), 50_00L));
        var second = tool.approveRefund(new RefundTool.ApproveRefundRequest(
                "tenant-1", "admin-1", created.refundId(), 50_00L));
        assertThat(first.status()).isEqualTo("APPROVED");
        assertThat(second.status()).isEqualTo("APPROVED");
        // 不应该有重复扣款
        assertThat(repo.count()).isEqualTo(1);
    }
}
