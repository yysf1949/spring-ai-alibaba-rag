package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 退款规则判定工具 — L1 只读，决定 {@code create_refund} 是否需要转人工。
 *
 * <h2>对齐「路条编程」文章 §"AI Agent 不能绕过原有业务规则"</h2>
 * <p>文章原话："组合优惠、跨店满减、平台券"等组合优惠订单退款流程复杂
 * （按比例分摊 / 部分退券 / 二次审核），Agent 不能自动处理，必须转人工。</p>
 *
 * <h2>硬编码 3 条规则（Phase 13b 演示用）</h2>
 * <ul>
 *   <li>{@code withinWindow} — 订单是否在 7 天退款期内（mock：用 shippedAt + 7 天）</li>
 *   <li>{@code hasComboCoupon} — 订单是否使用过组合优惠</li>
 *   <li>{@code requiresManual} — 上述任一命中 → 必须人工</li>
 * </ul>
 *
 * <h2>未来扩展（不在 Phase 13b 范围）</h2>
 * <p>完整退款规则引擎（DSL 表达式 / 按规则 ID 路由）属于 Phase 15+ 范围。
 * 当前硬编码 3 条规则足够演示 "不能绕过原业务规则" 这篇文章论点。</p>
 */
@Component
public class RefundRuleTool {

    /** 默认退款窗口（天）— 生产应从配置中心读取 */
    public static final int DEFAULT_REFUND_WINDOW_DAYS = 7;

    private final PaymentChannelTool paymentChannelTool;

    public RefundRuleTool(PaymentChannelTool paymentChannelTool) {
        this.paymentChannelTool = paymentChannelTool;
    }

    @ToolSpec(
            name = "check_refund_rules",
            description = "检查订单是否满足退款规则（退款期/组合优惠/支付渠道）。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public RefundRuleResult checkRefundRules(CheckRefundRulesRequest req) {
        List<String> matchedRules = new ArrayList<>();
        boolean requiresManual = false;
        String reason = null;

        // 规则 1: 退款窗口
        Instant shippedAt = SHIPPED_AT.get(req.orderId());
        if (shippedAt == null) {
            // 订单未发货 → 视为在退款期内
            matchedRules.add("within_window:not_shipped");
        } else if (Instant.now().isAfter(shippedAt.plusSeconds(
                DEFAULT_REFUND_WINDOW_DAYS * 24L * 3600L))) {
            requiresManual = true;
            reason = "refund_window_exceeded";
            matchedRules.add("refund_window_exceeded");
        } else {
            matchedRules.add("within_window:" + DEFAULT_REFUND_WINDOW_DAYS + "d");
        }

        // 规则 2: 组合优惠
        if (COMBO_COUPONS.contains(req.orderId())) {
            requiresManual = true;
            reason = (reason == null) ? "combo_coupon_requires_manual" : reason;
            matchedRules.add("has_combo_coupon");
        }

        // 规则 3: 支付渠道不允许退款
        PaymentChannelTool.PaymentChannelInfo channel =
                paymentChannelTool.queryPaymentChannel(
                        new PaymentChannelTool.QueryPaymentChannelRequest(req.orderId()));
        if (!channel.allowRefund()) {
            requiresManual = true;
            reason = (reason == null)
                    ? "payment_channel_blocks_refund:" + channel.channel()
                    : reason;
            matchedRules.add("channel_blocks:" + channel.channel());
        }

        return new RefundRuleResult(
                req.orderId(),
                !matchedRules.contains("refund_window_exceeded"),
                matchedRules.contains("has_combo_coupon"),
                requiresManual,
                reason,
                matchedRules);
    }

    /** 测试/种子用 — 标记订单已发货时间（用于退款窗口判定）。 */
    public void markShipped(String orderId, Instant shippedAt) {
        SHIPPED_AT.put(orderId, shippedAt);
    }

    /** 测试/种子用 — 标记订单使用了组合优惠（必须转人工）。 */
    public void markComboCoupon(String orderId) {
        COMBO_COUPONS.add(orderId);
    }

    public record CheckRefundRulesRequest(String orderId) { }
    public record RefundRuleResult(
            String orderId,
            boolean withinWindow,        // 退款期内
            boolean hasComboCoupon,      // 含组合优惠
            boolean requiresManual,      // 必须人工
            String reason,               // 首个原因（多原因按优先级）
            List<String> matchedRules    // 命中的规则 ID 列表
    ) { }

    private static final Map<String, Instant> SHIPPED_AT = new ConcurrentHashMap<>();
    private static final java.util.Set<String> COMBO_COUPONS = ConcurrentHashMap.newKeySet();
}