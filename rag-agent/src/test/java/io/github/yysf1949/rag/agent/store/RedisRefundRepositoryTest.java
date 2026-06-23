package io.github.yysf1949.rag.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisRefundRepositoryTest {

    private final JedisPooled jedis = mock(JedisPooled.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RedisStoreFactory factory = new RedisStoreFactory(jedis, mapper);
    private final RedisRefundRepository repo = new RedisRefundRepository(factory);

    @BeforeEach
    void setUp() {
        reset(jedis);
    }

    @Test
    void saveAndFind() throws Exception {
        var refund = new RefundRepositoryPort.RefundRecord("REF-1", "t1", "u1", "ORD-1", 50_00L, "退款", "APPROVED");

        repo.save(refund);

        String expectedKey = "agent:refund:t1:REF-1";
        verify(jedis).set(eq(expectedKey), anyString());

        String expectedJson = mapper.writeValueAsString(refund);
        when(jedis.get(expectedKey)).thenReturn(expectedJson);

        var found = repo.findByIdAndTenant("REF-1", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().refundId()).isEqualTo("REF-1");
        assertThat(found.get().status()).isEqualTo("APPROVED");
        assertThat(found.get().amountCents()).isEqualTo(50_00L);
    }

    @Test
    void findNotFound() {
        when(jedis.get(anyString())).thenReturn(null);

        var found = repo.findByIdAndTenant("NONEXISTENT", "t1");
        assertThat(found).isEmpty();
    }

    @Test
    void countRefunds() {
        when(jedis.keys("agent:refund:*")).thenReturn(Set.of(
                "agent:refund:t1:REF-1",
                "agent:refund:t1:REF-2",
                "agent:refund:t2:REF-3"
        ));

        int count = repo.count();
        assertThat(count).isEqualTo(3);
    }
}