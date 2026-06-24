package io.github.yysf1949.rag.agent.payment.store;

import io.github.yysf1949.rag.agent.payment.PaymentPort.Invoice;
import io.github.yysf1949.rag.agent.payment.PaymentPort.InvoiceStatus;
import io.github.yysf1949.rag.agent.payment.PaymentPort.PaymentMethod;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * H2 持久化 invoice 仓库 — {@code @Profile("h2")} 激活.
 *
 * <p>DDL 见 {@code rag-agent/src/main/resources/schema-h2.sql}. 使用 H2 MERGE 做 upsert.
 * 时间戳用 {@code BIGINT} epoch millis, 避免时区问题.</p>
 *
 * <h2>索引设计</h2>
 * <ul>
 *   <li>{@code idx_agent_invoice_tenant_created} — listByTenant 分页查询</li>
 *   <li>{@code idx_agent_invoice_external_ref} — webhook 反查 invoice</li>
 * </ul>
 */
@Component
@Profile("h2")
public class H2InvoiceStore implements InvoiceStore {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Invoice> MAPPER = (ResultSet rs, int row) -> new Invoice(
            rs.getString("invoice_id"),
            rs.getString("tenant_id"),
            rs.getLong("amount_cents"),
            rs.getString("currency"),
            InvoiceStatus.valueOf(rs.getString("status")),
            readLong(rs, "paid_at"),
            PaymentMethod.valueOf(rs.getString("payment_method")),
            rs.getString("external_ref"),
            rs.getString("description"),
            rs.getLong("created_at"),
            readLong(rs, "refunded_at"),
            rs.getString("refund_reason")
    );

    public H2InvoiceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Invoice save(Invoice invoice) {
        jdbc.update(
                "MERGE INTO agent_invoice (invoice_id, tenant_id, amount_cents, currency, status, "
                        + "paid_at, payment_method, external_ref, description, created_at, refunded_at, refund_reason) "
                        + "KEY(invoice_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                invoice.invoiceId(),
                invoice.tenantId(),
                invoice.amountCents(),
                invoice.currency(),
                invoice.status().name(),
                invoice.paidAt(),
                invoice.paymentMethod().name(),
                invoice.externalRef(),
                invoice.description(),
                invoice.createdAt(),
                invoice.refundedAt(),
                invoice.refundReason()
        );
        return invoice;
    }

    @Override
    public Optional<Invoice> findById(String tenantId, String invoiceId) {
        return jdbc.query(
                "SELECT * FROM agent_invoice WHERE invoice_id = ? AND tenant_id = ?",
                MAPPER, invoiceId, tenantId
        ).stream().findFirst();
    }

    @Override
    public Optional<Invoice> findByExternalRef(String externalRef) {
        return jdbc.query(
                "SELECT * FROM agent_invoice WHERE external_ref = ?",
                MAPPER, externalRef
        ).stream().findFirst();
    }

    @Override
    public List<Invoice> listByTenant(String tenantId, int limit) {
        return jdbc.query(
                "SELECT * FROM agent_invoice WHERE tenant_id = ? "
                        + "ORDER BY created_at DESC LIMIT ?",
                MAPPER, tenantId, limit
        );
    }

    @Override
    public long countByTenant(String tenantId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_invoice WHERE tenant_id = ?",
                Long.class, tenantId);
        return n == null ? 0L : n;
    }

    private static Long readLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}