package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.TicketRepositoryPort;
import io.github.yysf1949.rag.agent.store.entity.TicketEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Profile("mysql")
public class JpaTicketRepositoryAdapter implements TicketRepositoryPort {

    private final JpaTicketRepository jpa;

    public JpaTicketRepositoryAdapter(JpaTicketRepository jpa) { this.jpa = jpa; }

    @Override
    public TicketRecord save(TicketRecord ticket) {
        var entity = new TicketEntity(ticket.ticketId(), ticket.tenantId(), ticket.userId(),
                ticket.summary(), ticket.status(), ticket.createdAt());
        jpa.save(entity);
        return ticket;
    }

    @Override
    public Optional<TicketRecord> findById(String id) {
        return jpa.findByTicketId(id)
                .map(TicketEntity::toRecord);
    }

    @Override
    public List<TicketRecord> findByTenant(String tenantId) {
        return jpa.findByTenantId(tenantId)
                .stream()
                .map(TicketEntity::toRecord)
                .toList();
    }
}