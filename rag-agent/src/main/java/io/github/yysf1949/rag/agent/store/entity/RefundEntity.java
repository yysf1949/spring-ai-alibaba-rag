package io.github.yysf1949.rag.agent.store.entity;

import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import jakarta.persistence.*;

@Entity
@Table(name = "agent_refund")
public class RefundEntity {

    @Id @Column(length = 64)
    private String refundId;

    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String userId;

    @Column(nullable = false, length = 64)
    private String orderId;

    private long amountCents;

    @Column(length = 256)
    private String reason;

    @Column(length = 32)
    private String status;

    // default constructor for JPA
    public RefundEntity() {}

    public RefundEntity(String refundId, String tenantId, String userId, String orderId,
                        long amountCents, String reason, String status) {
        this.refundId = refundId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.orderId = orderId;
        this.amountCents = amountCents;
        this.reason = reason;
        this.status = status;
    }

    // getters
    public String refundId() { return refundId; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
    public String orderId() { return orderId; }
    public long amountCents() { return amountCents; }
    public String reason() { return reason; }
    public String status() { return status; }

    // to Port record
    public RefundRepositoryPort.RefundRecord toRecord() {
        return new RefundRepositoryPort.RefundRecord(refundId, tenantId, userId, orderId, amountCents, reason, status);
    }
}