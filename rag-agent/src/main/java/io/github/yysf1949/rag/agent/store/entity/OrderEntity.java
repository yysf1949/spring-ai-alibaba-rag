package io.github.yysf1949.rag.agent.store.entity;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import jakarta.persistence.*;

@Entity
@Table(name = "agent_order")
public class OrderEntity {

    @Id @Column(length = 64)
    private String orderId;

    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String userId;

    private long amountCents;

    @Column(length = 32)
    private String status;

    // default constructor for JPA
    public OrderEntity() {}

    public OrderEntity(String orderId, String tenantId, String userId, long amountCents, String status) {
        this.orderId = orderId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.amountCents = amountCents;
        this.status = status;
    }

    // getters
    public String orderId() { return orderId; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
    public long amountCents() { return amountCents; }
    public String status() { return status; }

    // to Port record
    public OrderRepositoryPort.OrderRecord toRecord() {
        return new OrderRepositoryPort.OrderRecord(orderId, tenantId, userId, amountCents, status);
    }
}