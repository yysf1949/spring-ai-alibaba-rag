package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import io.github.yysf1949.rag.agent.store.entity.OrderEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Profile("mysql")
public class JpaOrderRepositoryAdapter implements OrderRepositoryPort {

    private final JpaOrderRepository jpa;

    public JpaOrderRepositoryAdapter(JpaOrderRepository jpa) { this.jpa = jpa; }

    @Override
    public OrderRecord save(OrderRecord order) {
        var entity = new OrderEntity(order.orderId(), order.tenantId(), order.userId(),
                order.amountCents(), order.status());
        jpa.save(entity);
        return order;
    }

    @Override
    public Optional<OrderRecord> findByIdAndTenant(String orderId, String tenantId) {
        return jpa.findByOrderIdAndTenantId(orderId, tenantId)
                .map(OrderEntity::toRecord);
    }

    @Override
    public List<OrderRecord> findByUser(String tenantId, String userId) {
        return jpa.findByTenantIdAndUserId(tenantId, userId).stream()
                .map(OrderEntity::toRecord)
                .toList();
    }
}
