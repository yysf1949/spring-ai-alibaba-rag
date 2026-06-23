package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("h2")
public class H2CouponRepository implements CouponRepositoryPort {

    private final JdbcTemplate jdbc;
    private static final RowMapper<CouponRecord> MAPPER = (rs, row) -> new CouponRecord(
            rs.getString("coupon_id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("order_id"),
            rs.getLong("amount_cents"),
            rs.getString("reason_tag"),
            rs.getString("status")
    );

    public H2CouponRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public CouponRecord save(CouponRecord coupon) {
        jdbc.update("MERGE INTO agent_coupon (coupon_id, tenant_id, user_id, order_id, amount_cents, reason_tag, status) "
                        + "KEY(coupon_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                coupon.couponId(), coupon.tenantId(), coupon.userId(),
                coupon.orderId(), coupon.amountCents(), coupon.reasonTag(), coupon.status());
        return coupon;
    }

    @Override
    public List<CouponRecord> findActiveByTenantAndUser(String tenantId, String userId) {
        return jdbc.query(
                "SELECT * FROM agent_coupon WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE'",
                MAPPER, tenantId, userId);
    }
}