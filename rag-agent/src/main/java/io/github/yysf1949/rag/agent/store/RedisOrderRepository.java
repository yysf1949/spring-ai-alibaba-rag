package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@Profile("redis")
public class RedisOrderRepository implements OrderRepositoryPort {

    private final RedisStoreFactory factory;

    public RedisOrderRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public OrderRecord save(OrderRecord order) {
        try {
            String key = factory.key("order", order.tenantId(), order.orderId());
            String json = factory.mapper().writeValueAsString(order);
            factory.jedis().set(key, json);
            return order;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save order", e);
        }
    }

    @Override
    public Optional<OrderRecord> findByIdAndTenant(String orderId, String tenantId) {
        try {
            String key = factory.key("order", tenantId, orderId);
            String json = factory.jedis().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(factory.mapper().readValue(json, OrderRecord.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to find order", e);
        }
    }

    @Override
    public List<OrderRecord> findByUser(String tenantId, String userId) {
        try {
            List<OrderRecord> result = new ArrayList<>();
            String pattern = factory.key("order", tenantId, "*");
            var keys = factory.jedis().keys(pattern);
            for (String key : keys) {
                String json = factory.jedis().get(key);
                if (json != null) {
                    OrderRecord order = factory.mapper().readValue(json, OrderRecord.class);
                    if (userId.equals(order.userId())) {
                        result.add(order);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to find orders by user", e);
        }
    }
}
