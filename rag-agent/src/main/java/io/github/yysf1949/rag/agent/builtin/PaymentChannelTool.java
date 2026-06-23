package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 支付渠道查询工具 — L1 只读。
 *
 * <h2>对齐「路条编程」文章 §"AI Agent 不能绕过原有业务规则"</h2>
 * <p>"退款要走原支付渠道的退款流程" — 真实支付中台会按渠道返回不同的退款策略
 * （微信支付 30 天内可退/原路返回、虚拟卡充值不退、积分支付不退现金等）。
 * Agent 必须先查支付渠道，再决定能否走 {@code create_refund}。</p>
 *
 * <h2>当前为 mock 决策表</h2>
 * <p>生产应替换为真实支付中台 SDK 调用。本类提供静态策略表，避免在 OrderRepositoryPort
 * 上加 paymentChannel 字段（保持 Phase 11 既有契约不变）。</p>
 */
@Component
public class PaymentChannelTool {

    @ToolSpec(
            name = "query_payment_channel",
            description = "查询订单的支付渠道与退款能力（仅读，判定是否能走 create_refund）。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public PaymentChannelInfo queryPaymentChannel(QueryPaymentChannelRequest req) {
        Policy p = POLICIES.getOrDefault(req.orderId(),
                new Policy("WECHAT", true, "wechat-default"));
        return new PaymentChannelInfo(req.orderId(), p.channel(), p.allowRefund(), p.reason());
    }

    /** 注入订单级支付渠道（生产应改为按 userId/tenantId 查询真实支付中台）。 */
    public void registerPolicy(String orderId, String channel, boolean allowRefund, String reason) {
        POLICIES.put(orderId, new Policy(channel, allowRefund, reason));
    }

    public record QueryPaymentChannelRequest(String orderId) { }
    public record PaymentChannelInfo(
            String orderId,
            String channel,         // WECHAT / ALIPAY / CARD / POINTS / VIRTUAL_CARD
            boolean allowRefund,
            String reason           // allowRefund=false 时填原因
    ) { }

    private record Policy(String channel, boolean allowRefund, String reason) { }

    /** 默认策略表（生产应替换为真实支付中台 SDK）。 */
    private static final Map<String, Policy> POLICIES = new java.util.concurrent.ConcurrentHashMap<>();
}