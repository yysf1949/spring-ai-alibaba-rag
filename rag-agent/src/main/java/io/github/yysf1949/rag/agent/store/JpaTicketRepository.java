package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.store.entity.TicketEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@Profile("mysql")
public interface JpaTicketRepository extends JpaRepository<TicketEntity, String> {
    Optional<TicketEntity> findByTicketId(String ticketId);
    List<TicketEntity> findByTenantId(String tenantId);
}