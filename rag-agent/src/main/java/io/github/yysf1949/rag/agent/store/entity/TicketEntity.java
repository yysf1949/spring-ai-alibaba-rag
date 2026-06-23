package io.github.yysf1949.rag.agent.store.entity;

import io.github.yysf1949.rag.agent.builtin.port.TicketRepositoryPort;
import jakarta.persistence.*;

@Entity
@Table(name = "agent_ticket")
public class TicketEntity {

    @Id @Column(length = 64)
    private String ticketId;

    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String userId;

    @Column(length = 512)
    private String summary;

    @Column(length = 32)
    private String status;

    private long createdAt;

    // default constructor for JPA
    public TicketEntity() {}

    public TicketEntity(String ticketId, String tenantId, String userId, String summary,
                        String status, long createdAt) {
        this.ticketId = ticketId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.summary = summary;
        this.status = status;
        this.createdAt = createdAt;
    }

    // getters
    public String ticketId() { return ticketId; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
    public String summary() { return summary; }
    public String status() { return status; }
    public long createdAt() { return createdAt; }

    // to Port record
    public TicketRepositoryPort.TicketRecord toRecord() {
        return new TicketRepositoryPort.TicketRecord(ticketId, tenantId, userId, summary, status, createdAt);
    }
}