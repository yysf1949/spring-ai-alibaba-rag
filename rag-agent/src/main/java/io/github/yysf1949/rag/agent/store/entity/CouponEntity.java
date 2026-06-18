package io.github.yysf1949.rag.agent.store.entity;

import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import jakarta.persistence.*;

@Entity
@Table(name = "agent_coupon")
public class CouponEntity {

    @Id @Column(length = 64)
    private String couponId;

    @Column(nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String userId;

    @Column(length = 64)
    private String orderId;

    private long amountCents;

    @Column(length = 64)
    private String reasonTag;

    @Column(length = 32)
    private String status;

    // default constructor for JPA
    public CouponEntity() {}

    public CouponEntity(String couponId, String tenantId, String userId, String orderId,
                        long amountCents, String reasonTag, String status) {
        this.couponId = couponId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.orderId = orderId;
        this.amountCents = amountCents;
        this.reasonTag = reasonTag;
        this.status = status;
    }

    // getters
    public String couponId() { return couponId; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
    public String orderId() { return orderId; }
    public long amountCents() { return amountCents; }
    public String reasonTag() { return reasonTag; }
    public String status() { return status; }

    // to Port record
    public CouponRepositoryPort.CouponRecord toRecord() {
        return new CouponRepositoryPort.CouponRecord(couponId, tenantId, userId, orderId, amountCents, reasonTag, status);
    }
}