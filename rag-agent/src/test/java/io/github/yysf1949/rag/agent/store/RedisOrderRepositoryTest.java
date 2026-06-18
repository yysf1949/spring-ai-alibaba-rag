package io.github.yysf1949.rag.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisOrderRepositoryTest {

    private final JedisPooled jedis = mock(JedisPooled.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RedisStoreFactory factory = new RedisStoreFactory(jedis, mapper);
    private final RedisOrderRepository repo = new RedisOrderRepository(factory);

    @BeforeEach
    void setUp() {
        reset(jedis);
    }

    @Test
    void saveAndFind() throws Exception {
        var order = new OrderRepositoryPort.OrderRecord("ORD-1", "t1", "u1", 100_00L, "CREATED");

        repo.save(order);

        String expectedKey = "agent:order:t1:ORD-1";
        verify(jedis).set(eq(expectedKey), anyString());

        String expectedJson = mapper.writeValueAsString(order);
        when(jedis.get(expectedKey)).thenReturn(expectedJson);

        var found = repo.findByIdAndTenant("ORD-1", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().orderId()).isEqualTo("ORD-1");
        assertThat(found.get().status()).isEqualTo("CREATED");
        assertThat(found.get().amountCents()).isEqualTo(100_00L);
    }

    @Test
    void findNotFound() {
        when(jedis.get(anyString())).thenReturn(null);

        var found = repo.findByIdAndTenant("NONEXISTENT", "t1");
        assertThat(found).isEmpty();
    }

    @Test
    void overwriteExisting() throws Exception {
        var order1 = new OrderRepositoryPort.OrderRecord("ORD-1", "t1", "u1", 100_00L, "CREATED");
        var order2 = new OrderRepositoryPort.OrderRecord("ORD-1", "t1", "u1", 200_00L, "PAID");

        repo.save(order1);
        repo.save(order2);

        verify(jedis, times(2)).set(anyString(), anyString());

        String expectedKey = "agent:order:t1:ORD-1";
        String expectedJson = mapper.writeValueAsString(order2);
        when(jedis.get(expectedKey)).thenReturn(expectedJson);

        var found = repo.findByIdAndTenant("ORD-1", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().amountCents()).isEqualTo(200_00L);
        assertThat(found.get().status()).isEqualTo("PAID");
    }
}