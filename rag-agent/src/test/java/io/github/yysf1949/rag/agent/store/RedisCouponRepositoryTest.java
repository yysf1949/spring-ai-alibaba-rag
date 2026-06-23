package io.github.yysf1949.rag.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisCouponRepositoryTest {

    private final JedisPooled jedis = mock(JedisPooled.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RedisStoreFactory factory = new RedisStoreFactory(jedis, mapper);
    private final RedisCouponRepository repo = new RedisCouponRepository(factory);

    @BeforeEach
    void setUp() {
        reset(jedis);
    }

    @Test
    void saveAndFindActive() throws Exception {
        var coupon = new CouponRepositoryPort.CouponRecord("CPN-1", "t1", "u1", null, 20_00L, "新用户", "ACTIVE");

        repo.save(coupon);

        String expectedKey = "agent:coupon:t1:CPN-1";
        verify(jedis).set(eq(expectedKey), anyString());

        String prefix = "agent:coupon:t1:";
        String expectedJson = mapper.writeValueAsString(coupon);
        when(jedis.keys(prefix + "*")).thenReturn(Set.of(expectedKey));
        when(jedis.get(expectedKey)).thenReturn(expectedJson);

        var active = repo.findActiveByTenantAndUser("t1", "u1");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).couponId()).isEqualTo("CPN-1");
        assertThat(active.get(0).status()).isEqualTo("ACTIVE");
    }

    @Test
    void findActiveFiltersByUserAndStatus() throws Exception {
        var coupon1 = new CouponRepositoryPort.CouponRecord("CPN-1", "t1", "u1", null, 20_00L, "新用户", "ACTIVE");
        var coupon2 = new CouponRepositoryPort.CouponRecord("CPN-2", "t1", "u2", null, 10_00L, "老用户", "ACTIVE");
        var coupon3 = new CouponRepositoryPort.CouponRecord("CPN-3", "t1", "u1", "ORD-1", 5_00L, "已用", "USED");

        String prefix = "agent:coupon:t1:";
        when(jedis.keys(prefix + "*")).thenReturn(Set.of(
                "agent:coupon:t1:CPN-1",
                "agent:coupon:t1:CPN-2",
                "agent:coupon:t1:CPN-3"
        ));
        when(jedis.get("agent:coupon:t1:CPN-1")).thenReturn(mapper.writeValueAsString(coupon1));
        when(jedis.get("agent:coupon:t1:CPN-2")).thenReturn(mapper.writeValueAsString(coupon2));
        when(jedis.get("agent:coupon:t1:CPN-3")).thenReturn(mapper.writeValueAsString(coupon3));

        var active = repo.findActiveByTenantAndUser("t1", "u1");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).couponId()).isEqualTo("CPN-1");
    }

    @Test
    void findActiveReturnsEmptyWhenNoKeys() {
        String prefix = "agent:coupon:t1:";
        when(jedis.keys(prefix + "*")).thenReturn(Set.of());

        var active = repo.findActiveByTenantAndUser("t1", "u1");
        assertThat(active).isEmpty();
    }
}