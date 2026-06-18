package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 订单工具 — L1 查询 + L3 取消。
 *
 * <h2>4 级风险对照</h2>
 * <ul>
 *   <li>{@code get_order} — L1_READ（只读，参数含 amountCents 仅作展示）</li>
 *   <li>{@code cancel_order} — L3_BUSINESS_STATE（写业务态，单笔 ≤ 100 元不需转人工）</li>
 * </ul>
 *
 * <h2>maxAmountCents 含义</h2>
 * <p>对 cancel_order：单笔订单金额上限 100 元 = 10000 cents。超过此金额必须转人工审批。</p>
 */
@Component
public class OrderTool {

    /** 取消订单单笔金额上限（分）— 100 元 */
    public static final long CANCEL_MAX_AMOUNT_CENTS = 100_00L;

    private final OrderRepositoryPort repo;

    public OrderTool(OrderRepositoryPort repo) {
        this.repo = repo;
    }

    @ToolSpec(
            name = "get_order",
            description = "查询订单详情（订单号、金额、状态）。只读工具。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public GetOrderResponse getOrder(GetOrderRequest req) {
        var order = repo.findByIdAndTenant(req.orderId(), req.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + req.orderId()));
        return new GetOrderResponse(
                order.orderId(), order.status(), order.amountCents(), order.userId());
    }

    @ToolSpec(
            name = "cancel_order",
            description = "取消订单（未发货可取消，超过 100 元需转人工审批）。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = false,
            requiresIdempotencyKey = true,
            maxAmountCents = 100_00L  // 100 元上限
    )
    public CancelOrderResponse cancelOrder(CancelOrderRequest req) {
        var order = repo.findByIdAndTenant(req.orderId(), req.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + req.orderId()));
        // 幂等：已经是 CANCELLED 的订单，直接返回当前状态，不重复写
        if ("CANCELLED".equals(order.status())) {
            return new CancelOrderResponse(order.orderId(), order.status(), req.reason());
        }
        // 已发货/已送达都不能再取消
        if (!Set.of("CREATED", "PAID").contains(order.status())) {
            throw new IllegalStateException("Cannot cancel order in status: " + order.status());
        }
        var cancelled = new OrderRepositoryPort.OrderRecord(
                order.orderId(), order.tenantId(), order.userId(),
                order.amountCents(), "CANCELLED");
        repo.save(cancelled);
        return new CancelOrderResponse(order.orderId(), "CANCELLED", req.reason());
    }

    public record GetOrderRequest(String tenantId, String userId, String orderId) { }
    public record GetOrderResponse(String orderId, String status, long amountCents, String userId) { }
    public record CancelOrderRequest(String tenantId, String userId, String orderId,
                                     long amountCents, String reason) { }
    public record CancelOrderResponse(String orderId, String status, String reason) { }
}
