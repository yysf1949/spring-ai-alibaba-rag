package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import io.github.yysf1949.rag.agent.store.entity.CouponEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("mysql")
public class JpaCouponRepositoryAdapter implements CouponRepositoryPort {

    private final JpaCouponRepository jpa;

    public JpaCouponRepositoryAdapter(JpaCouponRepository jpa) { this.jpa = jpa; }

    @Override
    public CouponRecord save(CouponRecord coupon) {
        var entity = new CouponEntity(coupon.couponId(), coupon.tenantId(), coupon.userId(),
                coupon.orderId(), coupon.amountCents(), coupon.reasonTag(), coupon.status());
        jpa.save(entity);
        return coupon;
    }

    @Override
    public List<CouponRecord> findActiveByTenantAndUser(String tenantId, String userId) {
        return jpa.findByTenantIdAndUserIdAndStatus(tenantId, userId, "ACTIVE")
                .stream()
                .map(CouponEntity::toRecord)
                .toList();
    }
}