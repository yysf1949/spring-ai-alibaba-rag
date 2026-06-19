package io.github.yysf1949.rag.agent.builtin.store;
import org.springframework.context.annotation.Profile;

import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版退款仓储。
 */
@Repository
@Profile("default")
public class InMemoryRefundRepository implements RefundRepositoryPort {

    private final Map<String, RefundRepositoryPort.RefundRecord> store = new ConcurrentHashMap<>();

    @Override
    public RefundRepositoryPort.RefundRecord save(RefundRepositoryPort.RefundRecord refund) {
        store.put(refund.refundId(), refund);
        return refund;
    }

    @Override
    public Optional<RefundRepositoryPort.RefundRecord> findByIdAndTenant(String refundId, String tenantId) {
        RefundRepositoryPort.RefundRecord r = store.get(refundId);
        if (r == null) return Optional.empty();
        if (!r.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Cross-tenant refund access");
        }
        return Optional.of(r);
    }

    @Override
    public int count() { return store.size(); }
}
