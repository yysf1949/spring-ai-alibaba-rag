package io.github.yysf1949.rag.agent.builtin.port;

import java.util.List;
import java.util.Optional;

public interface TicketRepositoryPort {
    TicketRecord save(TicketRecord ticket);
    Optional<TicketRecord> findById(String id);
    List<TicketRecord> findByTenant(String tenantId);

    record TicketRecord(
            String ticketId,
            String tenantId,
            String userId,
            String summary,
            String status,
            long createdAt
    ) {}
}
