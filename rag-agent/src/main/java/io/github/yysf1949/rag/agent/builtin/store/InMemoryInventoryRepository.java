package io.github.yysf1949.rag.agent.builtin.store;
import org.springframework.context.annotation.Profile;

import io.github.yysf1949.rag.agent.builtin.port.InventoryPort;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商品库存 InMemory 实现 — 演示/测试用。
 */
import org.springframework.stereotype.Component;

@Component
@Profile("default")
public class InMemoryInventoryRepository implements InventoryPort {

    private final ConcurrentHashMap<String, InventoryPort.ProductStock> stocks = new ConcurrentHashMap<>();

    public void save(InventoryPort.ProductStock stock) {
        stocks.put(key(stock.tenantId(), stock.productId()), stock);
    }

    @Override
    public Optional<InventoryPort.ProductStock> findStock(String tenantId, String productId) {
        return Optional.ofNullable(stocks.get(key(tenantId, productId)));
    }

    private static String key(String tenantId, String productId) {
        return tenantId + ":" + productId;
    }
}