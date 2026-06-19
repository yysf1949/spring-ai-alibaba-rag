package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.service.OrderApplicationService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单工具 — L1 查询 + L1 列表 + L3 取消。
 *
 * <h2>4 级风险对照</h2>
 * <ul>
 *   <li>{@code get_order} — L1_READ（只读，参数含 amountCents 仅作展示）</li>
 *   <li>{@code list_orders} — L1_READ（只读，查用户订单列表）</li>
 *   <li>{@code cancel_order} — L3_BUSINESS_STATE（写业务态，单笔 ≤ 100 元不需转人工）</li>
 * </ul>
 *
 * <h2>委托模式</h2>
 * <p>本工具类仅负责 API 契约（参数/返回值），所有业务逻辑委托给
 * {@link OrderApplicationService}。Agent 和管理后台共享同一个领域服务，
 * 确保「不应该有一套给 Agent 用的简化逻辑，另一套给管理后台用的完整逻辑」。</p>
 */
@Component
public class OrderTool {

    /** 取消订单单笔金额上限（分）— 100 元 */
    public static final long CANCEL_MAX_AMOUNT_CENTS = 100_00L;

    private final OrderApplicationService orderApplicationService;

    public OrderTool(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    @ToolSpec(
            name = "get_order",
            description = "查询订单详情：返回订单号、金额(分)、状态(CREATED/PAID/SHIPPED/COMPLETED/CANCELLED)、用户ID。"
                    + "适用于：用户问'我的订单'、'订单到哪了'、'订单多少钱'。只读工具，不修改任何数据。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public GetOrderResponse getOrder(GetOrderRequest req) {
        // 委托给领域服务
        var order = orderApplicationService.getOrder(req.tenantId(), req.orderId());
        return new GetOrderResponse(
                order.orderId(), order.status(), order.amountCents(), order.userId());
    }

    @ToolSpec(
            name = "list_orders",
            description = "按用户查询订单列表，返回简要信息(订单号、金额、状态)。"
                    + "适用于：用户说'我最近的订单'、'查一下我的所有订单'。只读工具，用户可据此选择具体订单进一步操作。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public ListOrdersResponse listOrders(ListOrdersRequest req) {
        // 委托给领域服务
        var orders = orderApplicationService.listOrders(req.tenantId(), req.userId());
        var briefs = orders.stream()
                .map(o -> new OrderBrief(o.orderId(), o.status(), o.amountCents()))
                .toList();
        return new ListOrdersResponse(briefs, briefs.size());
    }

    @ToolSpec(
            name = "cancel_order",
            description = "取消CREATED或PAID状态的订单。单笔≤100元可自动执行，超过100元需转人工。"
                    + "幂等：已取消订单直接返回当前状态。不支持取消已发货/已签收订单。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = true,
            requiresIdempotencyKey = true,
            maxAmountCents = 100_00L,  // 100 元上限
            requiresConfirmationToken = true
    )
    public CancelOrderResponse cancelOrder(IdempotencyKey idempotencyKey, CancelOrderRequest req) {
        // 委托给领域服务 — Agent 和管理后台走同一条代码路径
        var record = orderApplicationService.cancelOrder(
                req.tenantId(), req.userId(), req.orderId(),
                req.amountCents(), req.reason(), idempotencyKey);
        return new CancelOrderResponse(record.orderId(), record.status(), req.reason());
    }

    public record GetOrderRequest(String tenantId, String userId, String orderId) { }
    public record GetOrderResponse(String orderId, String status, long amountCents, String userId) { }
    public record ListOrdersRequest(String tenantId, String userId) { }
    public record OrderBrief(String orderId, String status, long amountCents) { }
    public record ListOrdersResponse(List<OrderBrief> orders, int total) { }
    public record CancelOrderRequest(String tenantId, String userId, String orderId,
                                     long amountCents, String reason) { }
    public record CancelOrderResponse(String orderId, String status, String reason) { }
}
