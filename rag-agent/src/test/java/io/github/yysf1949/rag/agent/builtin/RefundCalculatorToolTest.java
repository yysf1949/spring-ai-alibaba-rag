package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 退款金额计算器测试 — 3 个用例对齐文章 "Agent 不能绕过业务规则" 原则。
 */
@ExtendWith(MockitoExtension.class)
class RefundCalculatorToolTest {

    @Mock
    private RefundRuleTool ruleTool;

    @Mock
    private OrderRepositoryPort orderRepo;

    @InjectMocks
    private RefundCalculatorTool tool;

    @Test
    void normalOrderReturnsFullAmount() {
        // 普通订单: 退款期内 + 无组合优惠 + 渠道允许 → max = 全额
        var order = new OrderRepositoryPort.OrderRecord(
                "ORD-1", "t1", "u1", 100_00L, "SHIPPED");
        when(orderRepo.findByIdAndTenant("ORD-1", "t1")).thenReturn(Optional.of(order));
        when(ruleTool.checkRefundRules(any()))
                .thenReturn(new RefundRuleTool.RefundRuleResult(
                        "ORD-1", true, false, false, null, List.of("within_window:7d")));

        var resp = tool.calculate(new RefundCalculatorTool.RefundCalcRequest("t1", "u1", "ORD-1"));

        assertThat(resp.originalAmountCents()).isEqualTo(100_00L);
        assertThat(resp.maxRefundableCents()).isEqualTo(100_00L);
        assertThat(resp.requiresManual()).isFalse();
        assertThat(resp.reason()).isNull();
    }

    @Test
    void comboCouponOrderReturns80Percent() {
        // 组合优惠订单: hasComboCoupon=true (但 requiresManual=false 走 mock 路径)
        // 注: 真实业务中组合优惠通常 requiresManual=true; 这里单独测 80% 分摊逻辑
        var order = new OrderRepositoryPort.OrderRecord(
                "ORD-2", "t1", "u1", 100_00L, "SHIPPED");
        when(orderRepo.findByIdAndTenant("ORD-2", "t1")).thenReturn(Optional.of(order));
        when(ruleTool.checkRefundRules(any()))
                .thenReturn(new RefundRuleTool.RefundRuleResult(
                        "ORD-2", true, true, false, "combo_coupon_80pct", List.of("has_combo_coupon")));

        var resp = tool.calculate(new RefundCalculatorTool.RefundCalcRequest("t1", "u1", "ORD-2"));

        assertThat(resp.originalAmountCents()).isEqualTo(100_00L);
        // 100_00 * 0.8 = 80_00
        assertThat(resp.maxRefundableCents()).isEqualTo(80_00L);
        assertThat(resp.reason()).isEqualTo("combo_coupon_80pct");
    }

    @Test
    void requiresManualReturnsZero() {
        // 任一规则命中 requiresManual=true → max = 0
        var order = new OrderRepositoryPort.OrderRecord(
                "ORD-3", "t1", "u1", 100_00L, "DELIVERED");
        when(orderRepo.findByIdAndTenant("ORD-3", "t1")).thenReturn(Optional.of(order));
        when(ruleTool.checkRefundRules(any()))
                .thenReturn(new RefundRuleTool.RefundRuleResult(
                        "ORD-3", false, false, true, "refund_window_exceeded",
                        List.of("refund_window_exceeded")));

        var resp = tool.calculate(new RefundCalculatorTool.RefundCalcRequest("t1", "u1", "ORD-3"));

        assertThat(resp.maxRefundableCents()).isEqualTo(0L);
        assertThat(resp.requiresManual()).isTrue();
        assertThat(resp.reason()).isEqualTo("refund_window_exceeded");
    }

    @Test
    void orderNotFoundThrowsIllegalArgument() {
        when(orderRepo.findByIdAndTenant("MISSING", "t1")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                tool.calculate(new RefundCalculatorTool.RefundCalcRequest("t1", "u1", "MISSING")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING");
    }
}