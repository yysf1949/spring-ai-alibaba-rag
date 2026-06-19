package io.github.yysf1949.rag.agent.builtin.store;
import org.springframework.context.annotation.Profile;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
@Profile("default")
public class InMemoryOrderRepository implements OrderRepositoryPort {

    private final ConcurrentHashMap<String, OrderRecord> orders = new ConcurrentHashMap<>();

    @Override
    public OrderRecord save(OrderRecord order) {
        orders.put(key(order.tenantId(), order.orderId()), order);
        return order;
    }

    @Override
    public Optional<OrderRecord> findByIdAndTenant(String orderId, String tenantId) {
        return Optional.ofNullable(orders.get(key(tenantId, orderId)));
    }

    @Override
    public List<OrderRecord> findByUser(String tenantId, String userId) {
        String prefix = tenantId + ":";
        return orders.values().stream()
                .filter(o -> o.tenantId().equals(tenantId) && o.userId().equals(userId))
                .toList();
    }

    private static String key(String tenantId, String orderId) {
        return tenantId + ":" + orderId;
    }
}