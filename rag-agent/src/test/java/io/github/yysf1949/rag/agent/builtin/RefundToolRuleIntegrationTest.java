package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.store.InMemoryRefundRepository;
import io.github.yysf1949.rag.agent.exception.HandoffRequiredException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 13b M5: RefundTool 集成 RefundRule 后, 命中规则必须抛 HandoffRequiredException
 * (而不是默默允许 Agent 直接退款 — 这正是文章"AI Agent 不能绕过原业务规则"的反例)。
 */
class RefundToolRuleIntegrationTest {

    private final InMemoryRefundRepository repo = new InMemoryRefundRepository();
    private final PaymentChannelTool channel = new PaymentChannelTool();
    private final RefundRuleTool ruleTool = new RefundRuleTool(channel);
    private final RefundTool tool = new RefundTool(repo, ruleTool);

    @Test
    void normalOrderAutoProcesses() {
        // 默认订单 — 未标记任何规则,应自动退款成功
        var resp = tool.createRefund(new RefundTool.CreateRefundRequest(
                "tenant-1", "user-1", "ORD-NORMAL", 100_00L, "质量问题"));
        assertThat(resp.status()).isEqualTo("PENDING");
        assertThat(repo.count()).isEqualTo(1);
    }

    @Test
    void comboCouponTriggersHandoffException() {
        // 组合优惠订单 → 必须人工
        ruleTool.markComboCoupon("ORD-COMBO");
        assertThatThrownBy(() ->
                tool.createRefund(new RefundTool.CreateRefundRequest(
                        "tenant-1", "user-1", "ORD-COMBO", 100_00L, "ok")))
                .isInstanceOf(HandoffRequiredException.class)
                .hasMessageContaining("create_refund")
                .hasMessageContaining("combo_coupon_requires_manual");

        // 关键: 抛异常时不应该写 Repo (业务规则拦截)
        assertThat(repo.count()).isZero();
    }

    @Test
    void windowExceededTriggersHandoffException() {
        // 发货超过 7 天
        ruleTool.markShipped("ORD-LATE", Instant.now().minus(10, ChronoUnit.DAYS));
        assertThatThrownBy(() ->
                tool.createRefund(new RefundTool.CreateRefundRequest(
                        "tenant-1", "user-1", "ORD-LATE", 100_00L, "ok")))
                .isInstanceOf(HandoffRequiredException.class)
                .hasMessageContaining("refund_window_exceeded");

        assertThat(repo.count()).isZero();
    }

    @Test
    void handoffExceptionCarriesMatchedRules() {
        // 验证 HandoffRequiredException 携带完整 matchedRules + riskNote (文章要求"前置工作证据")
        ruleTool.markComboCoupon("ORD-COMBO2");
        try {
            tool.createRefund(new RefundTool.CreateRefundRequest(
                    "tenant-1", "user-1", "ORD-COMBO2", 100_00L, "ok"));
        } catch (HandoffRequiredException e) {
            assertThat(e.toolName()).isEqualTo("create_refund");
            assertThat(e.matchedRules()).contains("has_combo_coupon");
            assertThat(e.riskNote()).contains("ORD-COMBO2").contains("combo_coupon");
            return;
        }
        org.junit.jupiter.api.Assertions.fail("Expected HandoffRequiredException");
    }
}