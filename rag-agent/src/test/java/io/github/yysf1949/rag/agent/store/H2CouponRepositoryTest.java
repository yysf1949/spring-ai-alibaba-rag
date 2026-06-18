package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class H2CouponRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final H2CouponRepository repo = new H2CouponRepository(jdbc);

    @Test
    void saveAndFindActive() {
        var coupon = new CouponRepositoryPort.CouponRecord("CPN-1", "t1", "u1", null, 20_00L, "新客优惠", "ACTIVE");

        repo.save(coupon);

        verify(jdbc).update(contains("MERGE INTO agent_coupon"), eq("CPN-1"), eq("t1"), eq("u1"),
                isNull(), eq(20_00L), eq("新客优惠"), eq("ACTIVE"));

        var row = mock(java.sql.ResultSet.class);
        when(jdbc.query(contains("SELECT * FROM agent_coupon"), any(RowMapper.class), eq("t1"), eq("u1")))
                .thenAnswer(inv -> {
                    RowMapper<CouponRepositoryPort.CouponRecord> mapper = inv.getArgument(1);
                    when(row.getString("coupon_id")).thenReturn("CPN-1");
                    when(row.getString("tenant_id")).thenReturn("t1");
                    when(row.getString("user_id")).thenReturn("u1");
                    when(row.getString("order_id")).thenReturn(null);
                    when(row.getLong("amount_cents")).thenReturn(20_00L);
                    when(row.getString("reason_tag")).thenReturn("新客优惠");
                    when(row.getString("status")).thenReturn("ACTIVE");
                    return List.of(mapper.mapRow(row, 0));
                });

        var active = repo.findActiveByTenantAndUser("t1", "u1");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).couponId()).isEqualTo("CPN-1");
        assertThat(active.get(0).status()).isEqualTo("ACTIVE");
    }

    @Test
    void findActiveReturnsEmptyWhenNone() {
        when(jdbc.query(contains("SELECT * FROM agent_coupon"), any(RowMapper.class), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        var active = repo.findActiveByTenantAndUser("t1", "u1");
        assertThat(active).isEmpty();
    }

    @Test
    void saveWithOrderId() {
        var coupon = new CouponRepositoryPort.CouponRecord("CPN-2", "t1", "u1", "ORD-2", 30_00L, "满减", "USED");

        repo.save(coupon);

        verify(jdbc).update(contains("MERGE INTO agent_coupon"), eq("CPN-2"), eq("t1"), eq("u1"),
                eq("ORD-2"), eq(30_00L), eq("满减"), eq("USED"));
    }
}