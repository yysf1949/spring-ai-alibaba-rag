package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.InventoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("redis")
public class RedisInventoryRepository implements InventoryPort {

    private final RedisStoreFactory factory;

    public RedisInventoryRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public Optional<ProductStock> findStock(String tenantId, String productId) {
        try {
            String key = factory.key("inventory", tenantId, productId);
            String json = factory.jedis().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(factory.mapper().readValue(json, ProductStock.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to find stock", e);
        }
    }
}
