package io.github.yysf1949.rag.agent.builtin.port;

import java.util.Optional;

/**
 * 商品库存查询 Port — 松耦合接口。
 *
 * <p>对齐文章"后端能力"：判断商品是否有货、是否可换货，
 * 是售后场景（换货/补发）的前置条件。</p>
 */
public interface InventoryPort {

    Optional<ProductStock> findStock(String tenantId, String productId);

    record ProductStock(
            String productId,
            String tenantId,
            String productName,
            int availableQuantity,
            boolean onSale,
            long priceCents,
            String category
    ) {}
}
