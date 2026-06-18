package io.github.yysf1949.rag.agent.builtin;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版优惠券仓储。
 */
@Repository
public class CouponRepository {

    private final Map<String, Coupon> store = new ConcurrentHashMap<>();

    public Coupon save(Coupon coupon) {
        store.put(coupon.couponId(), coupon);
        return coupon;
    }

    public List<Coupon> findActiveByTenantAndUser(String tenantId, String userId) {
        return store.values().stream()
                .filter(c -> c.tenantId().equals(tenantId))
                .filter(c -> c.userId().equals(userId))
                .filter(c -> "ACTIVE".equals(c.status()))
                .collect(Collectors.toList());
    }

    public record Coupon(
            String couponId,
            String tenantId,
            String userId,
            String orderId,
            long amountCents,
            String reasonTag,
            String status  // ACTIVE / USED / EXPIRED
    ) { }

    public static String newCouponId() { return "CPN-" + UUID.randomUUID().toString().substring(0, 8); }
}
