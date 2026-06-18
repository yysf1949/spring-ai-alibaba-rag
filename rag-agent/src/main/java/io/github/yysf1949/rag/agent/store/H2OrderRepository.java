package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Profile("h2")
public class H2OrderRepository implements OrderRepositoryPort {

    private final JdbcTemplate jdbc;
    private static final RowMapper<OrderRecord> MAPPER = (rs, row) -> new OrderRecord(
            rs.getString("order_id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getLong("amount_cents"),
            rs.getString("status")
    );

    public H2OrderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public OrderRecord save(OrderRecord order) {
        jdbc.update("MERGE INTO agent_order (order_id, tenant_id, user_id, amount_cents, status) "
                + "KEY(order_id) VALUES (?, ?, ?, ?, ?)",
                order.orderId(), order.tenantId(), order.userId(),
                order.amountCents(), order.status());
        return order;
    }

    @Override
    public Optional<OrderRecord> findByIdAndTenant(String orderId, String tenantId) {
        return jdbc.query("SELECT * FROM agent_order WHERE order_id = ? AND tenant_id = ?",
                MAPPER, orderId, tenantId).stream().findFirst();
    }

    @Override
    public List<OrderRecord> findByUser(String tenantId, String userId) {
        return jdbc.query("SELECT * FROM agent_order WHERE tenant_id = ? AND user_id = ?",
                MAPPER, tenantId, userId);
    }
}
