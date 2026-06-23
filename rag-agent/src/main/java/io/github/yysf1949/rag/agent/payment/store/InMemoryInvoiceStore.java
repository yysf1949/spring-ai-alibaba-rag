package io.github.yysf1949.rag.agent.payment.store;

import io.github.yysf1949.rag.agent.payment.PaymentPort.Invoice;
import io.github.yysf1949.rag.agent.payment.PaymentPort.PaymentMethod;
import io.github.yysf1949.rag.agent.payment.exception.InvoiceNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invoice 内存仓库 — {@code @Profile("default")} 激活.
 *
 * <p>双索引: invoiceId → Invoice, externalRef → invoiceId. 后者给 webhook 路由用
 * (网关只给 externalRef, 需要反查 invoice). 跟 Phase 37 HandoffStore Redis 的 pattern 一致.</p>
 *
 * <p>线程安全: 用 ConcurrentHashMap + compute 操作保证原子性.</p>
 */
@Component
@Profile("default")
public class InMemoryInvoiceStore implements InvoiceStore {

    private final Map<String, Invoice> byId = new ConcurrentHashMap<>();
    private final Map<String, String> externalRefToId = new ConcurrentHashMap<>();

    @Override
    public Invoice save(Invoice invoice) {
        byId.put(invoice.invoiceId(), invoice);
        if (invoice.externalRef() != null) {
            externalRefToId.put(invoice.externalRef(), invoice.invoiceId());
        }
        return invoice;
    }

    @Override
    public Optional<Invoice> findById(String tenantId, String invoiceId) {
        Invoice i = byId.get(invoiceId);
        if (i != null && i.tenantId().equals(tenantId)) {
            return Optional.of(i);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Invoice> findByExternalRef(String externalRef) {
        String id = externalRefToId.get(externalRef);
        if (id == null) return Optional.empty();
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Invoice> listByTenant(String tenantId, int limit) {
        List<Invoice> out = new ArrayList<>();
        for (Invoice i : byId.values()) {
            if (i.tenantId().equals(tenantId)) {
                out.add(i);
            }
        }
        out.sort(Comparator.comparingLong(Invoice::createdAt).reversed());
        if (out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    @Override
    public long countByTenant(String tenantId) {
        long n = 0;
        for (Invoice i : byId.values()) {
            if (i.tenantId().equals(tenantId)) n++;
        }
        return n;
    }

    public Invoice requireById(String tenantId, String invoiceId) {
        return findById(tenantId, invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(
                        "invoice not found: tenantId=" + tenantId + " invoiceId=" + invoiceId));
    }

    public static PaymentMethod parseMethod(String s) {
        return PaymentMethod.of(s);
    }
}
