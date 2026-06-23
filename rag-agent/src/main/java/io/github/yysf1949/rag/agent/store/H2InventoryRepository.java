package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.InventoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("h2")
public class H2InventoryRepository implements InventoryPort {

    private final JdbcTemplate jdbc;
    private static final RowMapper<ProductStock> MAPPER = (rs, row) -> new ProductStock(
            rs.getString("product_id"),
            rs.getString("tenant_id"),
            rs.getString("product_name"),
            rs.getInt("available_quantity"),
            rs.getBoolean("on_sale"),
            rs.getLong("price_cents"),
            rs.getString("category")
    );

    public H2InventoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 预填充库存数据（供测试 / 初始化用）。
     */
    public void save(ProductStock stock) {
        jdbc.update("MERGE INTO agent_inventory (product_id, tenant_id, product_name, available_quantity, on_sale, price_cents, category) "
                        + "KEY(product_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                stock.productId(), stock.tenantId(), stock.productName(),
                stock.availableQuantity(), stock.onSale(), stock.priceCents(), stock.category());
    }

    @Override
    public Optional<ProductStock> findStock(String tenantId, String productId) {
        return jdbc.query("SELECT * FROM agent_inventory WHERE tenant_id = ? AND product_id = ?",
                MAPPER, tenantId, productId).stream().findFirst();
    }
}
