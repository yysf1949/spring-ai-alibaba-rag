package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.AfterServiceAuditPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Profile("h2")
public class H2AfterServiceAuditRepository implements AfterServiceAuditPort {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AuditRecord> MAPPER = (rs, row) -> {
        String stepsRaw = rs.getString("steps");
        List<String> steps = stepsRaw == null || stepsRaw.isBlank()
                ? List.of()
                : Arrays.stream(stepsRaw.split("\\|\\|")).collect(Collectors.toList());
        return new AuditRecord(
                rs.getString("audit_id"),
                rs.getString("order_id"),
                rs.getString("action_type"),
                steps,
                rs.getBoolean("success"),
                rs.getLong("created_at")
        );
    };

    public H2AfterServiceAuditRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public AuditRecord save(AuditRecord record) {
        String stepsJoined = record.steps() == null ? "" : String.join("||", record.steps());
        jdbc.update("MERGE INTO agent_after_service_audit (audit_id, order_id, action_type, steps, success, created_at) "
                        + "KEY(audit_id) VALUES (?, ?, ?, ?, ?, ?)",
                record.auditId(), record.orderId(), record.actionType(),
                stepsJoined, record.success(), record.createdAt());
        return record;
    }

    @Override
    public List<AuditRecord> findByOrder(String orderId) {
        return jdbc.query("SELECT * FROM agent_after_service_audit WHERE order_id = ?",
                MAPPER, orderId);
    }
}
