package io.github.yysf1949.rag.agent.builtin.port;

import java.util.Optional;

/** Phase 11: Repository Port — 松耦合, 让 OrderTool 不依赖具体存储实现（InMemory/Redis）。 */
public interface OrderRepositoryPort {
    OrderRecord save(OrderRecord order);
    Optional<OrderRecord> findByIdAndTenant(String orderId, String tenantId);

    record OrderRecord(
            String orderId,
            String tenantId,
            String userId,
            long amountCents,
            String status
    ) {}
}
