package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("h2")
public class H2RefundRepository implements RefundRepositoryPort {

    private final JdbcTemplate jdbc;
    private static final RowMapper<RefundRecord> MAPPER = (rs, row) -> new RefundRecord(
            rs.getString("refund_id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("order_id"),
            rs.getLong("amount_cents"),
            rs.getString("reason"),
            rs.getString("status")
    );

    public H2RefundRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public RefundRecord save(RefundRecord refund) {
        jdbc.update("MERGE INTO agent_refund (refund_id, tenant_id, user_id, order_id, amount_cents, reason, status) "
                        + "KEY(refund_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                refund.refundId(), refund.tenantId(), refund.userId(),
                refund.orderId(), refund.amountCents(), refund.reason(), refund.status());
        return refund;
    }

    @Override
    public Optional<RefundRecord> findByIdAndTenant(String refundId, String tenantId) {
        return jdbc.query("SELECT * FROM agent_refund WHERE refund_id = ? AND tenant_id = ?",
                MAPPER, refundId, tenantId).stream().findFirst();
    }

    @Override
    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM agent_refund", Integer.class);
        return n != null ? n : 0;
    }
}