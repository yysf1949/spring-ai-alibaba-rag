package io.github.yysf1949.rag.agent.payment.store;

import io.github.yysf1949.rag.agent.payment.PaymentPort.Invoice;

import java.util.List;
import java.util.Optional;

/**
 * Invoice 持久化层 (端口) — Phase 40 T4.
 *
 * <p>接口独立于实现, 存储可换 (InMemory / H2 / Redis). Adapter 通过此 Port 读写 invoice.</p>
 *
 * <h2>租户隔离</h2>
 * <p>所有 query 方法强制 tenantId 匹配 — 跨租户返回 Optional.empty() / 空列表.</p>
 */
public interface InvoiceStore {

    Invoice save(Invoice invoice);

    Optional<Invoice> findById(String tenantId, String invoiceId);

    Optional<Invoice> findByExternalRef(String externalRef);

    List<Invoice> listByTenant(String tenantId, int limit);

    long countByTenant(String tenantId);
}
