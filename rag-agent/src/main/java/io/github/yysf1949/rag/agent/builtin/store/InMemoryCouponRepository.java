package io.github.yysf1949.rag.agent.builtin.store;
import org.springframework.context.annotation.Profile;

import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版优惠券仓储。
 */
@Repository
@Profile("default")
public class InMemoryCouponRepository implements CouponRepositoryPort {

    private final Map<String, CouponRepositoryPort.CouponRecord> store = new ConcurrentHashMap<>();

    @Override
    public CouponRepositoryPort.CouponRecord save(CouponRepositoryPort.CouponRecord coupon) {
        store.put(coupon.couponId(), coupon);
        return coupon;
    }

    @Override
    public List<CouponRepositoryPort.CouponRecord> findActiveByTenantAndUser(String tenantId, String userId) {
        return store.values().stream()
                .filter(c -> c.tenantId().equals(tenantId))
                .filter(c -> c.userId().equals(userId))
                .filter(c -> "ACTIVE".equals(c.status()))
                .collect(Collectors.toList());
    }
}
