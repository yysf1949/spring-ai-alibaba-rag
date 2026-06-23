package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import io.github.yysf1949.rag.agent.store.entity.RefundEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("mysql")
public class JpaRefundRepositoryAdapter implements RefundRepositoryPort {

    private final JpaRefundRepository jpa;

    public JpaRefundRepositoryAdapter(JpaRefundRepository jpa) { this.jpa = jpa; }

    @Override
    public RefundRecord save(RefundRecord refund) {
        var entity = new RefundEntity(refund.refundId(), refund.tenantId(), refund.userId(),
                refund.orderId(), refund.amountCents(), refund.reason(), refund.status());
        jpa.save(entity);
        return refund;
    }

    @Override
    public Optional<RefundRecord> findByIdAndTenant(String refundId, String tenantId) {
        return jpa.findByRefundIdAndTenantId(refundId, tenantId)
                .map(RefundEntity::toRecord);
    }

    @Override
    public int count() {
        return (int) jpa.count();
    }
}