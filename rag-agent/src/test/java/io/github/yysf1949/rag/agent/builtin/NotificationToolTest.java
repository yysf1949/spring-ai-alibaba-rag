package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.NotificationTool;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryNotificationRepository;
import io.github.yysf1949.rag.agent.builtin.port.NotificationRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 站内通知工具测试 — 5 个用例对齐文章 "发送短信/站内通知" 能力。
 */
class NotificationToolTest {

    private InMemoryNotificationRepository repo;
    private NotificationTool tool;

    @BeforeEach
    void setUp() {
        repo = new InMemoryNotificationRepository();
        tool = new NotificationTool(repo);
    }

    @Test
    void whitelistedTemplateSendsSuccessfully() {
        var resp = tool.send(new NotificationTool.SendNotificationRequest(
                "t1", "u1", "REFUND_CREATED", "Your refund of ¥100 is created", "tok-1"));

        assertThat(resp.notificationId()).startsWith("NTF-");
        assertThat(resp.status()).isEqualTo("SENT");
    }

    @Test
    void unknownTemplateThrowsIllegalArgument() {
        assertThatThrownBy(() -> tool.send(new NotificationTool.SendNotificationRequest(
                "t1", "u1", "RANDOM_TEMPLATE", "hi", "tok-2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RANDOM_TEMPLATE");
    }

    @Test
    void contentExceeding500CharsThrowsIllegalArgument() {
        String tooLong = "x".repeat(501);
        assertThatThrownBy(() -> tool.send(new NotificationTool.SendNotificationRequest(
                "t1", "u1", "REFUND_CREATED", tooLong, "tok-3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    @Test
    void duplicateWithin5MinReturnsDedupe() {
        // 第一次
        var first = tool.send(new NotificationTool.SendNotificationRequest(
                "t1", "u1", "COUPON_ISSUED", "Coupon ¥20 issued", "tok-4"));
        assertThat(first.status()).isEqualTo("SENT");

        // 5 分钟内同 user + 同 template → 命中去重
        var second = tool.send(new NotificationTool.SendNotificationRequest(
                "t1", "u1", "COUPON_ISSUED", "Coupon ¥20 issued (retry)", "tok-4"));
        assertThat(second.status()).isEqualTo("DEDUPED");
        assertThat(second.notificationId()).isEqualTo(first.notificationId());
    }

    @Test
    void differentUsersAndTemplatesAreIndependent() {
        // u1 + REFUND_CREATED
        var r1 = tool.send(new NotificationTool.SendNotificationRequest(
                "t1", "u1", "REFUND_CREATED", "msg1", "tok-5"));
        // u1 + COUPON_ISSUED (不同 template)
        var r2 = tool.send(new NotificationTool.SendNotificationRequest(
                "t1", "u1", "COUPON_ISSUED", "msg2", "tok-6"));
        // u2 + REFUND_CREATED (不同 user)
        var r3 = tool.send(new NotificationTool.SendNotificationRequest(
                "t1", "u2", "REFUND_CREATED", "msg3", "tok-7"));

        // 全部应 SENT (无去重)
        assertThat(r1.status()).isEqualTo("SENT");
        assertThat(r2.status()).isEqualTo("SENT");
        assertThat(r3.status()).isEqualTo("SENT");
        // 各自不同 ID
        assertThat(r1.notificationId()).isNotEqualTo(r2.notificationId());
        assertThat(r1.notificationId()).isNotEqualTo(r3.notificationId());
    }
}