package io.github.yysf1949.rag.agent.payment.store;

import io.github.yysf1949.rag.agent.payment.PaymentPort.Invoice;
import io.github.yysf1949.rag.agent.payment.PaymentPort.InvoiceStatus;
import io.github.yysf1949.rag.agent.payment.PaymentPort.PaymentMethod;
import io.github.yysf1949.rag.agent.store.StoreAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2InvoiceStore 集成测试 — 真实 H2 内存库 (非 mock).
 */
class H2InvoiceStoreTest {

    private JdbcTemplate jdbc;
    private H2InvoiceStore store;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:test_invoice_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        StoreAutoConfiguration.ensureAllSchema(jdbc);
        store = new H2InvoiceStore(jdbc);
    }

    @Test
    void saveAndFindByIdRoundTrips() {
        Invoice inv = Invoice.pending("INV-h2-001", "t1", 999, "USD",
                PaymentMethod.STRIPE, "cs_test_abc123", "Test payment");
        store.save(inv);

        var found = store.findById("t1", "INV-h2-001");
        assertThat(found).isPresent();
        assertThat(found.get().amountCents()).isEqualTo(999);
        assertThat(found.get().currency()).isEqualTo("USD");
        assertThat(found.get().status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(found.get().paymentMethod()).isEqualTo(PaymentMethod.STRIPE);
        assertThat(found.get().externalRef()).isEqualTo("cs_test_abc123");
    }

    @Test
    void saveAndFindByIdCrossTenantReturnsEmpty() {
        Invoice inv = Invoice.pending("INV-h2-002", "tenant-x", 500, "CNY",
                PaymentMethod.WECHAT, "wx_prepay_001", "CNY test");
        store.save(inv);

        assertThat(store.findById("tenant-y", "INV-h2-002")).isEmpty();
        assertThat(store.findById("tenant-x", "INV-h2-002")).isPresent();
    }

    @Test
    void findByExternalRefWorks() {
        Invoice inv = Invoice.pending("INV-h2-003", "t1", 1000, "USD",
                PaymentMethod.STRIPE, "cs_test_ref456", "Ref lookup test");
        store.save(inv);

        var found = store.findByExternalRef("cs_test_ref456");
        assertThat(found).isPresent();
        assertThat(found.get().invoiceId()).isEqualTo("INV-h2-003");
    }

    @Test
    void listByTenantReturnsNewestFirst() throws InterruptedException {
        Invoice i1 = Invoice.pending("INV-h2-l1", "t1", 100, "USD",
                PaymentMethod.STRIPE, "cs_1", "first");
        Thread.sleep(10);
        Invoice i2 = Invoice.pending("INV-h2-l2", "t1", 200, "USD",
                PaymentMethod.STRIPE, "cs_2", "second");
        store.save(i1);
        store.save(i2);

        var list = store.listByTenant("t1", 10);
        assertThat(list).hasSize(2);
        // newest first
        assertThat(list.get(0).createdAt()).isGreaterThanOrEqualTo(list.get(1).createdAt());
    }

    @Test
    void saveAndMarkPaidUpdatesStatus() {
        Invoice inv = Invoice.pending("INV-h2-004", "t1", 5000, "USD",
                PaymentMethod.STRIPE, "cs_pay_test", "webhook test");
        store.save(inv);

        Invoice paid = inv.markPaid(1700000000000L);
        store.save(paid);

        var found = store.findById("t1", "INV-h2-004");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(found.get().paidAt()).isEqualTo(1700000000000L);
    }
}
