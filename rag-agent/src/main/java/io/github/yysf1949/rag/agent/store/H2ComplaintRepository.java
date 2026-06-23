package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.ComplaintRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("h2")
public class H2ComplaintRepository implements ComplaintRepositoryPort {

    private final JdbcTemplate jdbc;
    private static final RowMapper<ComplaintRecord> MAPPER = (rs, row) -> new ComplaintRecord(
            rs.getString("complaint_id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("order_id"),
            rs.getString("category"),
            rs.getString("description"),
            rs.getString("priority"),
            rs.getString("status"),
            rs.getLong("created_at")
    );

    public H2ComplaintRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public ComplaintRecord save(ComplaintRecord complaint) {
        jdbc.update("MERGE INTO agent_complaint (complaint_id, tenant_id, user_id, order_id, category, description, priority, status, created_at) "
                        + "KEY(complaint_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                complaint.complaintId(), complaint.tenantId(), complaint.userId(),
                complaint.orderId(), complaint.category(), complaint.description(),
                complaint.priority(), complaint.status(), complaint.createdAt());
        return complaint;
    }

    @Override
    public Optional<ComplaintRecord> findByIdAndTenant(String complaintId, String tenantId) {
        return jdbc.query("SELECT * FROM agent_complaint WHERE complaint_id = ? AND tenant_id = ?",
                MAPPER, complaintId, tenantId).stream().findFirst();
    }
}
