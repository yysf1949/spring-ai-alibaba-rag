package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.ComplaintRepositoryPort;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投诉工单 H2 集成测试 — 使用真实 H2 内存数据库，非 mock。
 */
class H2ComplaintRepositoryTest {

    private static JdbcTemplate jdbc;
    private static H2ComplaintRepository repo;

    @BeforeAll
    static void setUp() {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:test_complaint_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        StoreAutoConfiguration.ensureAllSchema(jdbc);
        repo = new H2ComplaintRepository(jdbc);
    }

    @Test
    void saveAndFindById() {
        var complaint = new ComplaintRepositoryPort.ComplaintRecord(
                "CMP-001", "t1", "u1", "ORD-100",
                "商品质量", "收到的商品有破损", "P1", "OPEN", 1700000000000L);

        repo.save(complaint);

        var found = repo.findByIdAndTenant("CMP-001", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().complaintId()).isEqualTo("CMP-001");
        assertThat(found.get().tenantId()).isEqualTo("t1");
        assertThat(found.get().userId()).isEqualTo("u1");
        assertThat(found.get().orderId()).isEqualTo("ORD-100");
        assertThat(found.get().category()).isEqualTo("商品质量");
        assertThat(found.get().description()).isEqualTo("收到的商品有破损");
        assertThat(found.get().priority()).isEqualTo("P1");
        assertThat(found.get().status()).isEqualTo("OPEN");
        assertThat(found.get().createdAt()).isEqualTo(1700000000000L);
    }

    @Test
    void findNotFound() {
        var found = repo.findByIdAndTenant("CMP-NONEXISTENT", "t1");
        assertThat(found).isEmpty();
    }

    @Test
    void saveOverwritesExisting() {
        var complaint1 = new ComplaintRepositoryPort.ComplaintRecord(
                "CMP-002", "t1", "u1", "ORD-200",
                "服务态度", "客服态度差", "P2", "OPEN", 1700000000000L);
        var complaint2 = new ComplaintRepositoryPort.ComplaintRecord(
                "CMP-002", "t1", "u1", "ORD-200",
                "服务态度", "客服态度差", "P2", "RESOLVED", 1700000060000L);

        repo.save(complaint1);
        repo.save(complaint2);

        var found = repo.findByIdAndTenant("CMP-002", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo("RESOLVED");
    }
}
