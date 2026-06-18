package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.InventoryPort;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品库存 H2 集成测试 — 使用真实 H2 内存数据库，非 mock。
 */
class H2InventoryRepositoryTest {

    private static JdbcTemplate jdbc;
    private static H2InventoryRepository repo;

    @BeforeAll
    static void setUp() {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:test_inventory_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        StoreAutoConfiguration.ensureAllSchema(jdbc);
        repo = new H2InventoryRepository(jdbc);
    }

    @Test
    void saveAndFindStock() {
        var stock = new InventoryPort.ProductStock(
                "SKU-001", "t1", "测试商品A", 100, true, 2999L, "电子产品");

        repo.save(stock);

        var found = repo.findStock("t1", "SKU-001");
        assertThat(found).isPresent();
        assertThat(found.get().productId()).isEqualTo("SKU-001");
        assertThat(found.get().tenantId()).isEqualTo("t1");
        assertThat(found.get().productName()).isEqualTo("测试商品A");
        assertThat(found.get().availableQuantity()).isEqualTo(100);
        assertThat(found.get().onSale()).isTrue();
        assertThat(found.get().priceCents()).isEqualTo(2999L);
        assertThat(found.get().category()).isEqualTo("电子产品");
    }

    @Test
    void findStockNotFound() {
        var found = repo.findStock("t1", "SKU-NONEXISTENT");
        assertThat(found).isEmpty();
    }

    @Test
    void saveUpdatesExistingStock() {
        var stock1 = new InventoryPort.ProductStock(
                "SKU-002", "t1", "测试商品B", 50, true, 1999L, "日用品");
        var stock2 = new InventoryPort.ProductStock(
                "SKU-002", "t1", "测试商品B", 30, true, 1999L, "日用品");

        repo.save(stock1);
        repo.save(stock2);

        var found = repo.findStock("t1", "SKU-002");
        assertThat(found).isPresent();
        assertThat(found.get().availableQuantity()).isEqualTo(30);
    }
}
