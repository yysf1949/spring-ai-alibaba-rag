package io.github.yysf1949.rag.agent.builtin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付渠道查询工具测试 — 3 个用例对齐"先查支付渠道再决定能否退款"。
 */
class PaymentChannelToolTest {

    private final PaymentChannelTool tool = new PaymentChannelTool();

    @Test
    void defaultOrderUsesWechatWithRefundAllowed() {
        var info = tool.queryPaymentChannel(
                new PaymentChannelTool.QueryPaymentChannelRequest("O-DEFAULT"));
        assertThat(info.channel()).isEqualTo("WECHAT");
        assertThat(info.allowRefund()).isTrue();
    }

    @Test
    void virtualCardDisallowsRefund() {
        tool.registerPolicy("O-VCARD", "VIRTUAL_CARD", false, "virtual_card_no_refund");
        var info = tool.queryPaymentChannel(
                new PaymentChannelTool.QueryPaymentChannelRequest("O-VCARD"));
        assertThat(info.allowRefund()).isFalse();
        assertThat(info.reason()).contains("virtual_card");
    }

    @Test
    void customPolicyOverridesDefault() {
        tool.registerPolicy("O-CUSTOM", "POINTS", false, "points_no_cash_refund");
        var info = tool.queryPaymentChannel(
                new PaymentChannelTool.QueryPaymentChannelRequest("O-CUSTOM"));
        assertThat(info.channel()).isEqualTo("POINTS");
        assertThat(info.allowRefund()).isFalse();
    }
}