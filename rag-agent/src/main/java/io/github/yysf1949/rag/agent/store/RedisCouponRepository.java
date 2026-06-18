package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@Profile("redis")
public class RedisCouponRepository implements CouponRepositoryPort {

    private final RedisStoreFactory factory;

    public RedisCouponRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public CouponRecord save(CouponRecord coupon) {
        try {
            String key = factory.key("coupon", coupon.tenantId(), coupon.couponId());
            String json = factory.mapper().writeValueAsString(coupon);
            factory.jedis().set(key, json);
            return coupon;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save coupon", e);
        }
    }

    @Override
    public List<CouponRecord> findActiveByTenantAndUser(String tenantId, String userId) {
        try {
            String prefix = factory.tenantPrefix("coupon", tenantId);
            Set<String> keys = factory.jedis().keys(prefix + "*");
            List<CouponRecord> result = new ArrayList<>();
            if (keys != null) {
                for (String key : keys) {
                    String json = factory.jedis().get(key);
                    if (json != null) {
                        CouponRecord coupon = factory.mapper().readValue(json, CouponRecord.class);
                        if ("ACTIVE".equals(coupon.status()) && userId.equals(coupon.userId())) {
                            result.add(coupon);
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to find active coupons", e);
        }
    }
}
