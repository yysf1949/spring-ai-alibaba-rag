package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.TicketRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Profile("h2")
public class H2TicketRepository implements TicketRepositoryPort {

    private final JdbcTemplate jdbc;
    private static final RowMapper<TicketRecord> MAPPER = (rs, row) -> new TicketRecord(
            rs.getString("ticket_id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("summary"),
            rs.getString("status"),
            rs.getLong("created_at")
    );

    public H2TicketRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public TicketRecord save(TicketRecord ticket) {
        jdbc.update("MERGE INTO agent_ticket (ticket_id, tenant_id, user_id, summary, status, created_at) "
                        + "KEY(ticket_id) VALUES (?, ?, ?, ?, ?, ?)",
                ticket.ticketId(), ticket.tenantId(), ticket.userId(),
                ticket.summary(), ticket.status(), ticket.createdAt());
        return ticket;
    }

    @Override
    public Optional<TicketRecord> findById(String id) {
        return jdbc.query("SELECT * FROM agent_ticket WHERE ticket_id = ?",
                MAPPER, id).stream().findFirst();
    }

    @Override
    public List<TicketRecord> findByTenant(String tenantId) {
        return jdbc.query("SELECT * FROM agent_ticket WHERE tenant_id = ?",
                MAPPER, tenantId);
    }
}