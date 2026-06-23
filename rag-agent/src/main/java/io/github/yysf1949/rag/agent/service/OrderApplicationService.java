package io.github.yysf1949.rag.agent.service;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort.OrderRecord;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 订单领域服务 — Agent 工具和管理后台共用的唯一订单操作入口。
 *
 * <p>对齐「路条编程」文章核心论点：「Agent 应该调用和管理后台相同的领域服务」
 * 「不应该有一套给 Agent 用的简化逻辑，另一套给管理后台用的完整逻辑」。</p>
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>订单状态校验 — 只有 CREATED/PAID 可取消</li>
 *   <li>幂等保护 — 委托 {@link IdempotencyStore}</li>
 *   <li>指标埋点 — 业务失败时记录 {@link AgentMetrics#recordBusinessError}</li>
 * </ul>
 */
@Component
public class OrderApplicationService {

    private static final Set<String> CANCELLABLE_STATUSES = Set.of("CREATED", "PAID");

    private final OrderRepositoryPort orderRepository;
    private final IdempotencyStore idempotencyStore;
    private final AgentMetrics agentMetrics;

    public OrderApplicationService(OrderRepositoryPort orderRepository,
                                   IdempotencyStore idempotencyStore,
                                   AgentMetrics agentMetrics) {
        this.orderRepository = orderRepository;
        this.idempotencyStore = idempotencyStore;
        this.agentMetrics = agentMetrics;
    }

    /**
     * 取消订单。
     *
     * <ol>
     *   <li>幂等检查 — 重复 key 返回已有结果</li>
     *   <li>订单状态校验 — 只有 CREATED/PAID 可取消</li>
     *   <li>写入取消状态，回填幂等结果</li>
     * </ol>
     */
    public OrderRecord cancelOrder(String tenantId, String userId, String orderId,
                                   long amountCents, String reason,
                                   IdempotencyKey idempotencyKey) {
        // 幂等检查
        IdempotencyStore.PutResult put = idempotencyStore.putIfAbsent(idempotencyKey, null);
        if (put.isReplay()) {
            String existingStatus = (String) put.value();
            if (existingStatus != null) {
                // 从 repo 取完整记录返回
                return orderRepository.findByIdAndTenant(orderId, tenantId)
                        .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
            }
        }

        OrderRecord order = orderRepository.findByIdAndTenant(orderId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        // 幂等：已经是 CANCELLED 的订单，直接返回当前状态
        if ("CANCELLED".equals(order.status())) {
            return order;
        }

        // 状态校验 — 只有 CREATED/PAID 可取消
        if (!CANCELLABLE_STATUSES.contains(order.status())) {
            agentMetrics.recordBusinessError("cancel_order", "INVALID_STATUS");
            throw new IllegalStateException("Cannot cancel order in status: " + order.status());
        }

        var cancelled = new OrderRecord(
                order.orderId(), order.tenantId(), order.userId(),
                order.amountCents(), "CANCELLED");
        OrderRecord saved = orderRepository.save(cancelled);

        // 回填幂等结果
        idempotencyStore.replace(idempotencyKey, "CANCELLED");
        return saved;
    }

    /**
     * 查询订单详情。
     */
    public OrderRecord getOrder(String tenantId, String orderId) {
        return orderRepository.findByIdAndTenant(orderId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    /**
     * 查询用户订单列表。
     */
    public List<OrderRecord> listOrders(String tenantId, String userId) {
        return orderRepository.findByUser(tenantId, userId);
    }
}
