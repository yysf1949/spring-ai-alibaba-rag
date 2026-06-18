package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.store.InMemoryAfterServiceAuditRepository;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfterServiceToolTest {

    private InMemoryAfterServiceAuditRepository auditRepo;
    private InMemoryNotificationRepository notificationRepo;
    private AfterServiceTool tool;

    @BeforeEach
    void setUp() {
        auditRepo = new InMemoryAfterServiceAuditRepository();
        notificationRepo = new InMemoryNotificationRepository();
        tool = new AfterServiceTool(auditRepo, notificationRepo);
    }

    @Test
    void refundConfirmedRecordsAuditAndSendsNotification() {
        var resp = tool.execute(new AfterServiceTool.AfterServiceRequest(
                "tenant-1", "user-1", "ORD-1", "REFUND_CONFIRMED", 200_00L, null));

        assertThat(resp.auditId()).startsWith("AUD-");
        assertThat(resp.actionType()).isEqualTo("REFUND_CONFIRMED");
        assertThat(resp.success()).isTrue();
        assertThat(resp.steps()).hasSize(3);
        assertThat(resp.steps().get(0)).contains("退款已确认");
        assertThat(resp.steps().get(2)).contains("REFUND_CREATED");

        // 审计记录已持久化
        var audits = auditRepo.findByOrder("ORD-1");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).actionType()).isEqualTo("REFUND_CONFIRMED");
        assertThat(audits.get(0).success()).isTrue();

        // 通知已发送
        assertThat(notificationRepo.existsByUserAndTemplateWithinWindow(
                "user-1", "REFUND_CREATED", java.time.Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void cancelConfirmedRecordsAuditAndSendsNotification() {
        var resp = tool.execute(new AfterServiceTool.AfterServiceRequest(
                "tenant-1", "user-1", "ORD-2", "CANCEL_CONFIRMED", 0, null));

        assertThat(resp.actionType()).isEqualTo("CANCEL_CONFIRMED");
        assertThat(resp.success()).isTrue();
        assertThat(resp.steps()).hasSize(3);
        assertThat(resp.steps().get(0)).contains("订单取消已确认");
        assertThat(resp.steps().get(2)).contains("ORDER_CANCELLED");

        var audits = auditRepo.findByOrder("ORD-2");
        assertThat(audits).hasSize(1);
    }

    @Test
    void complaintEscalatedRecordsAuditAndSendsNotification() {
        var resp = tool.execute(new AfterServiceTool.AfterServiceRequest(
                "tenant-1", "user-1", "ORD-3", "COMPLAINT_ESCALATED", 0, "物流延迟三天"));

        assertThat(resp.actionType()).isEqualTo("COMPLAINT_ESCALATED");
        assertThat(resp.success()).isTrue();
        assertThat(resp.steps()).hasSize(3);
        assertThat(resp.steps().get(0)).contains("投诉已升级");
        assertThat(resp.steps().get(0)).contains("物流延迟三天");
        assertThat(resp.steps().get(2)).contains("HUMAN_HANDOFF");

        var audits = auditRepo.findByOrder("ORD-3");
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).actionType()).isEqualTo("COMPLAINT_ESCALATED");
    }

    @Test
    void unknownActionTypeThrows() {
        assertThatThrownBy(() ->
                tool.execute(new AfterServiceTool.AfterServiceRequest(
                        "tenant-1", "user-1", "ORD-4", "UNKNOWN_ACTION", 0, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown actionType");
    }

    @Test
    void emptyContextDoesNotCrash() {
        // 验证 null escalationReason 不会 NPE
        var resp = tool.execute(new AfterServiceTool.AfterServiceRequest(
                "tenant-1", "user-1", "ORD-5", "COMPLAINT_ESCALATED", 0, null));
        assertThat(resp.success()).isTrue();
    }
}
