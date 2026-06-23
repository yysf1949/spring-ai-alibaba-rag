package io.github.yysf1949.rag.agent.builtin;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款规则判定工具测试 — 4 个用例对齐文章"组合优惠/退款期/支付渠道"3 大规则。
 */
class RefundRuleToolTest {

    private final PaymentChannelTool channel = new PaymentChannelTool();
    private final RefundRuleTool rule;

    RefundRuleToolTest() {
        this.rule = new RefundRuleTool(channel);
    }

    @Test
    void unshippedOrderWithinWindowAndAllowsAuto() {
        // 未发货、未用组合优惠 → 全部允许自动
        var result = rule.checkRefundRules(
                new RefundRuleTool.CheckRefundRulesRequest("O-1"));
        assertThat(result.withinWindow()).isTrue();
        assertThat(result.hasComboCoupon()).isFalse();
        assertThat(result.requiresManual()).isFalse();
        assertThat(result.matchedRules()).anyMatch(r -> r.startsWith("within_window"));
    }

    @Test
    void shippedBeyondWindowRequiresManual() {
        // 发货超过 7 天 → 超出退款期,必须人工
        rule.markShipped("O-2", Instant.now().minus(10, ChronoUnit.DAYS));
        var result = rule.checkRefundRules(
                new RefundRuleTool.CheckRefundRulesRequest("O-2"));
        assertThat(result.withinWindow()).isFalse();
        assertThat(result.requiresManual()).isTrue();
        assertThat(result.reason()).isEqualTo("refund_window_exceeded");
        assertThat(result.matchedRules()).contains("refund_window_exceeded");
    }

    @Test
    void comboCouponRequiresManual() {
        // 组合优惠订单 → 即使在退款期也要人工
        rule.markComboCoupon("O-3");
        var result = rule.checkRefundRules(
                new RefundRuleTool.CheckRefundRulesRequest("O-3"));
        assertThat(result.hasComboCoupon()).isTrue();
        assertThat(result.requiresManual()).isTrue();
        assertThat(result.reason()).isEqualTo("combo_coupon_requires_manual");
    }

    @Test
    void channelBlocksRefundRequiresManual() {
        // 虚拟卡充值 → 渠道不允许退款
        channel.registerPolicy("O-4", "VIRTUAL_CARD", false, "virtual_card_no_refund");
        var result = rule.checkRefundRules(
                new RefundRuleTool.CheckRefundRulesRequest("O-4"));
        assertThat(result.requiresManual()).isTrue();
        assertThat(result.reason()).contains("payment_channel_blocks_refund");
    }

    @Test
    void multipleRulesAllReported() {
        // 多规则同时命中 → matchedRules 全列出,reason 取首个
        rule.markShipped("O-5", Instant.now().minus(10, ChronoUnit.DAYS));
        rule.markComboCoupon("O-5");
        var result = rule.checkRefundRules(
                new RefundRuleTool.CheckRefundRulesRequest("O-5"));
        assertThat(result.requiresManual()).isTrue();
        assertThat(result.matchedRules()).contains("refund_window_exceeded", "has_combo_coupon");
    }
}