package io.github.yysf1949.rag.agent.builtin.port;

import java.util.List;
import java.util.Optional;

public interface CouponRepositoryPort {
    CouponRecord save(CouponRecord coupon);
    List<CouponRecord> findActiveByTenantAndUser(String tenantId, String userId);

    record CouponRecord(
            String couponId,
            String tenantId,
            String userId,
            String orderId,
            long amountCents,
            String reasonTag,
            String status
    ) {}

    static String newCouponId() { return "CPN-" + java.util.UUID.randomUUID().toString().substring(0, 8); }
}
