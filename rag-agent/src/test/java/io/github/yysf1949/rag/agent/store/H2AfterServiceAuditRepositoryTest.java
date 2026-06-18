package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.AfterServiceAuditPort;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 售后善后审计 H2 集成测试 — 使用真实 H2 内存数据库，非 mock。
 */
class H2AfterServiceAuditRepositoryTest {

    private static JdbcTemplate jdbc;
    private static H2AfterServiceAuditRepository repo;

    @BeforeAll
    static void setUp() {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:test_audit_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        StoreAutoConfiguration.ensureAllSchema(jdbc);
        repo = new H2AfterServiceAuditRepository(jdbc);
    }

    @Test
    void saveAndFindByOrder() {
        var record = new AfterServiceAuditPort.AuditRecord(
                "AUD-001", "ORD-100", "REFUND_CONFIRMED",
                List.of("验证退款金额", "更新订单状态", "发送通知"),
                true, 1700000000000L);

        repo.save(record);

        var found = repo.findByOrder("ORD-100");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).auditId()).isEqualTo("AUD-001");
        assertThat(found.get(0).orderId()).isEqualTo("ORD-100");
        assertThat(found.get(0).actionType()).isEqualTo("REFUND_CONFIRMED");
        assertThat(found.get(0).steps()).containsExactly(
                "验证退款金额", "更新订单状态", "发送通知");
        assertThat(found.get(0).success()).isTrue();
        assertThat(found.get(0).createdAt()).isEqualTo(1700000000000L);
    }

    @Test
    void findByOrderReturnsEmptyWhenNone() {
        var found = repo.findByOrder("ORD-NONEXISTENT");
        assertThat(found).isEmpty();
    }

    @Test
    void multipleAuditsForSameOrder() {
        repo.save(new AfterServiceAuditPort.AuditRecord(
                "AUD-002", "ORD-200", "CANCEL_CONFIRMED",
                List.of("取消订单", "退款处理"),
                true, 1700000000000L));
        repo.save(new AfterServiceAuditPort.AuditRecord(
                "AUD-003", "ORD-200", "COMPLAINT_ESCALATED",
                List.of("升级投诉", "分配主管"),
                false, 1700000060000L));

        var found = repo.findByOrder("ORD-200");
        assertThat(found).hasSize(2);
        assertThat(found).extracting(AfterServiceAuditPort.AuditRecord::auditId)
                .containsExactlyInAnyOrder("AUD-002", "AUD-003");
    }
}
