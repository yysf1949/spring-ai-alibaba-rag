package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 退款金额计算器 — L1 只读, Agent 在 {@code create_refund} 前调用此工具
 * 算出"最大可退金额", 避免 LLM 自己猜金额绕过业务规则。
 *
 * <h2>对齐「路条编程」文章 §"AI Agent 不能绕过原有业务规则"</h2>
 * <p>文章原话: Agent 不应该"根据自己的判断随意更新订单表",
 * 计算金额是业务规则的一部分, 应当由后端业务系统根据"订单金额 + 退款期 +
 * 组合优惠 + 支付渠道"算出 maxRefundable, Agent 仅作为 LLM 给出调用建议。</p>
 *
 * <h2>计算规则 (Phase 14 mock, 真实业务替换为财务系统调用)</h2>
 * <ul>
 *   <li>规则命中 requiresManual (任一: 退款期外/组合优惠/渠道不允许) → max = 0</li>
 *   <li>组合优惠订单 → max = 80% (按比例分摊, mock)</li>
 *   <li>其他 → max = order.amountCents()</li>
 * </ul>
 */
@Component
public class RefundCalculatorTool {

    /** 组合优惠订单退款比例 (mock: 80% 分摊) */
    public static final double COMBO_COUPON_REFUND_RATIO = 0.8;

    private final RefundRuleTool ruleTool;
    private final OrderRepositoryPort orderRepo;

    public RefundCalculatorTool(RefundRuleTool ruleTool, OrderRepositoryPort orderRepo) {
        this.ruleTool = Objects.requireNonNull(ruleTool, "ruleTool");
        this.orderRepo = Objects.requireNonNull(orderRepo, "orderRepo");
    }

    @ToolSpec(
            name = "calculate_refund_amount",
            description = "计算订单可退款金额上限（基于支付渠道 + 退款期 + 组合优惠规则）。Agent 在调用 create_refund 前应先调用此工具获取 maxRefundableCents。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public RefundCalcResponse calculate(RefundCalcRequest req) {
        Objects.requireNonNull(req, "req");
        var order = orderRepo.findByIdAndTenant(req.orderId(), req.tenantId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Order not found: " + req.orderId() + " (tenant=" + req.tenantId() + ")"));

        var rule = ruleTool.checkRefundRules(
                new RefundRuleTool.CheckRefundRulesRequest(req.orderId()));

        long maxRefundable;
        if (rule.requiresManual()) {
            // 任一规则命中 → 拒退 → max = 0
            maxRefundable = 0L;
        } else if (rule.hasComboCoupon()) {
            // 组合优惠按比例分摊
            maxRefundable = (long) (order.amountCents() * COMBO_COUPON_REFUND_RATIO);
        } else {
            maxRefundable = order.amountCents();
        }

        return new RefundCalcResponse(
                req.orderId(),
                order.amountCents(),
                maxRefundable,
                rule.requiresManual(),
                rule.reason());
    }

    public record RefundCalcRequest(String tenantId, String userId, String orderId) { }

    public record RefundCalcResponse(
            String orderId,
            long originalAmountCents,
            long maxRefundableCents,
            boolean requiresManual,
            String reason
    ) { }
}