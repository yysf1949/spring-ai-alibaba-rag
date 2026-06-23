package io.github.yysf1949.rag.agent.service;

import io.github.yysf1949.rag.agent.builtin.PaymentChannelTool;
import io.github.yysf1949.rag.agent.builtin.RefundRuleTool;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort.RefundRecord;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryRefundRepository;
import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
import io.github.yysf1949.rag.agent.exception.HandoffRequiredException;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundApplicationServiceTest {

    private InMemoryRefundRepository repo;
    private InMemoryIdempotencyStore idemStore;
    private RefundApplicationService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryRefundRepository();
        idemStore = new InMemoryIdempotencyStore();
        var channel = new PaymentChannelTool();
        var ruleTool = new RefundRuleTool(channel);
        var metrics = new AgentMetrics(new SimpleMeterRegistry());
        service = new RefundApplicationService(repo, ruleTool, idemStore, metrics);
    }

    private IdempotencyKey key(String token) {
        return IdempotencyKey.of("tenant-1", "user-1", "s1", "create_refund", token);
    }

    @Test
    void createRefundNormalFlow() {
        RefundRecord record = service.createRefund(
                "tenant-1", "user-1", "ORD-1", 50_00L, "质量问题", key("refund-1"));

        assertThat(record.refundId()).startsWith("REF-");
        assertThat(record.status()).isEqualTo("PENDING");
        assertThat(record.amountCents()).isEqualTo(50_00L);
        assertThat(record.tenantId()).isEqualTo("tenant-1");
        assertThat(record.userId()).isEqualTo("user-1");
        assertThat(record.orderId()).isEqualTo("ORD-1");
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void createRefundIdempotentReturnsSameResult() {
        IdempotencyKey idemKey = key("refund-idem");

        RefundRecord first = service.createRefund(
                "tenant-1", "user-1", "ORD-1", 50_00L, "质量问题", idemKey);
        RefundRecord second = service.createRefund(
                "tenant-1", "user-1", "ORD-1", 50_00L, "质量问题", idemKey);

        assertThat(first.refundId()).isEqualTo(second.refundId());
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void createRefundOverAmountLimitRecordsBusinessError() {
        assertThatThrownBy(() ->
                service.createRefund(
                        "tenant-1", "user-1", "ORD-1", 1000_00L, "高额退款", key("refund-over")))
                .isInstanceOf(AmountLimitExceededException.class);

        // 不应该写入任何记录
        assertThat(repo.count()).isZero();
    }

    @Test
    void approveRefundNormalFlow() {
        RefundRecord created = service.createRefund(
                "tenant-1", "user-1", "ORD-1", 50_00L, "ok", key("refund-approve"));

        RefundRecord approved = service.approveRefund(
                created.refundId(), "tenant-1", "admin-1");

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.refundId()).isEqualTo(created.refundId());
    }

    @Test
    void approveRefundIdempotentWhenAlreadyApproved() {
        RefundRecord created = service.createRefund(
                "tenant-1", "user-1", "ORD-1", 50_00L, "ok", key("refund-approve2"));

        service.approveRefund(created.refundId(), "tenant-1", "admin-1");
        RefundRecord second = service.approveRefund(created.refundId(), "tenant-1", "admin-1");

        assertThat(second.status()).isEqualTo("APPROVED");
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void cancelRefundNormalFlow() {
        RefundRecord created = service.createRefund(
                "tenant-1", "user-1", "ORD-1", 50_00L, "ok", key("refund-cancel"));

        RefundRecord cancelled = service.cancelRefund(
                created.refundId(), "tenant-1", "用户取消");

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.reason()).isEqualTo("用户取消");
    }

    @Test
    void cancelRefundIdempotentWhenAlreadyCancelled() {
        RefundRecord created = service.createRefund(
                "tenant-1", "user-1", "ORD-1", 50_00L, "ok", key("refund-cancel2"));

        service.cancelRefund(created.refundId(), "tenant-1", "用户取消");
        RefundRecord second = service.cancelRefund(created.refundId(), "tenant-1", "用户取消");

        assertThat(second.status()).isEqualTo("CANCELLED");
        assertThat(repo.count()).isEqualTo(1);
    }
}
