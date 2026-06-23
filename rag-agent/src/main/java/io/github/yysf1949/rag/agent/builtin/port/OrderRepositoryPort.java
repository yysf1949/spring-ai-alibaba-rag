package io.github.yysf1949.rag.agent.builtin.port;

import java.util.List;
import java.util.Optional;

/** Phase 11: Repository Port — 松耦合, 让 OrderTool 不依赖具体存储实现（InMemory/Redis）。 */
public interface OrderRepositoryPort {
    OrderRecord save(OrderRecord order);
    Optional<OrderRecord> findByIdAndTenant(String orderId, String tenantId);

    /** Phase 21: 查询用户订单列表 — list_orders 工具需要 */
    List<OrderRecord> findByUser(String tenantId, String userId);

    record OrderRecord(
            String orderId,
            String tenantId,
            String userId,
            long amountCents,
            String status
    ) {}
}
