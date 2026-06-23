package io.github.yysf1949.rag.agent.builtin.port;

import java.util.Optional;

public interface RefundRepositoryPort {
    RefundRecord save(RefundRecord refund);
    Optional<RefundRecord> findByIdAndTenant(String refundId, String tenantId);
    int count();

    record RefundRecord(
            String refundId,
            String tenantId,
            String userId,
            String orderId,
            long amountCents,
            String reason,
            String status
    ) {}

    static String newRefundId() { return "REF-" + java.util.UUID.randomUUID().toString().substring(0, 8); }
}
