package io.github.yysf1949.rag.agent.builtin;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版退款仓储。
 */
@Repository
public class RefundRepository {

    private final Map<String, Refund> store = new ConcurrentHashMap<>();

    public Refund save(Refund refund) {
        store.put(refund.refundId(), refund);
        return refund;
    }

    public Optional<Refund> findByIdAndTenant(String refundId, String tenantId) {
        Refund r = store.get(refundId);
        if (r == null) return Optional.empty();
        if (!r.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException("Cross-tenant refund access");
        }
        return Optional.of(r);
    }

    public int count() { return store.size(); }

    public record Refund(
            String refundId,
            String tenantId,
            String userId,
            String orderId,
            long amountCents,
            String reason,
            String status  // PENDING / APPROVED / REJECTED
    ) { }

    public static String newRefundId() { return "REF-" + UUID.randomUUID().toString().substring(0, 8); }
}
