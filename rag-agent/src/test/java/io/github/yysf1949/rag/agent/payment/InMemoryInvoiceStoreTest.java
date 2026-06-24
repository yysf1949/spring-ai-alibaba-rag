package io.github.yysf1949.rag.agent.payment;

import io.github.yysf1949.rag.agent.payment.PaymentPort.Invoice;
import io.github.yysf1949.rag.agent.payment.PaymentPort.InvoiceStatus;
import io.github.yysf1949.rag.agent.payment.PaymentPort.PaymentMethod;
import io.github.yysf1949.rag.agent.payment.store.InMemoryInvoiceStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InMemoryInvoiceStore 单元测试.
 */
class InMemoryInvoiceStoreTest {

    private final InMemoryInvoiceStore store = new InMemoryInvoiceStore();

    @Test
    void saveAndFindById() {
        Invoice inv = Invoice.pending("INV-mem-001", "t1", 5000, "USD",
                PaymentMethod.STRIPE, "cs_mem_1", "test");
        store.save(inv);

        assertThat(store.findById("t1", "INV-mem-001")).isPresent();
        assertThat(store.findById("t2", "INV-mem-001")).isEmpty();
    }

    @Test
    void findByExternalRef() {
        Invoice inv = Invoice.pending("INV-mem-002", "t1", 100, "CNY",
                PaymentMethod.WECHAT, "wx_prepay_002", "wx test");
        store.save(inv);

        assertThat(store.findByExternalRef("wx_prepay_002")).isPresent();
        assertThat(store.findByExternalRef("not_exists")).isEmpty();
    }

    @Test
    void listByTenantScoped() {
        store.save(Invoice.pending("INV-mem-l1", "t1", 100, "USD", PaymentMethod.STRIPE, "cs_1", ""));
        store.save(Invoice.pending("INV-mem-l2", "t1", 200, "USD", PaymentMethod.STRIPE, "cs_2", ""));
        store.save(Invoice.pending("INV-mem-l3", "t2", 300, "USD", PaymentMethod.STRIPE, "cs_3", ""));

        assertThat(store.listByTenant("t1", 10)).hasSize(2);
        assertThat(store.listByTenant("t2", 10)).hasSize(1);
    }

    @Test
    void saveAndMarkRefundedUpdatesStatus() {
        Invoice inv = Invoice.pending("INV-mem-003", "t1", 999, "USD",
                PaymentMethod.STRIPE, "cs_refund_1", "refund test");
        store.save(inv);

        Invoice paid = inv.markPaid(1700000000000L);
        store.save(paid);

        Invoice refunded = paid.markRefunded(1700001000000L, "customer request");
        store.save(refunded);

        var found = store.findById("t1", "INV-mem-003");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(InvoiceStatus.REFUNDED);
        assertThat(found.get().refundReason()).isEqualTo("customer request");
    }
}
