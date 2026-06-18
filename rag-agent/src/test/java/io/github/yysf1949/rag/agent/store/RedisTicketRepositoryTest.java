package io.github.yysf1949.rag.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.builtin.port.TicketRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisTicketRepositoryTest {

    private final JedisPooled jedis = mock(JedisPooled.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RedisStoreFactory factory = new RedisStoreFactory(jedis, mapper);
    private final RedisTicketRepository repo = new RedisTicketRepository(factory);

    @BeforeEach
    void setUp() {
        reset(jedis);
    }

    @Test
    void saveAndFindById() throws Exception {
        var ticket = new TicketRepositoryPort.TicketRecord("TKT-1", "t1", "u1", "测试工单", "OPEN", 1000L);

        repo.save(ticket);

        String expectedKey = "agent:ticket:TKT-1";
        verify(jedis).set(eq(expectedKey), anyString());

        String expectedJson = mapper.writeValueAsString(ticket);
        when(jedis.get(expectedKey)).thenReturn(expectedJson);

        var found = repo.findById("TKT-1");
        assertThat(found).isPresent();
        assertThat(found.get().ticketId()).isEqualTo("TKT-1");
        assertThat(found.get().status()).isEqualTo("OPEN");
        assertThat(found.get().summary()).isEqualTo("测试工单");
    }

    @Test
    void findByIdNotFound() {
        when(jedis.get(anyString())).thenReturn(null);

        var found = repo.findById("NONEXISTENT");
        assertThat(found).isEmpty();
    }

    @Test
    void findByTenant() throws Exception {
        var ticket1 = new TicketRepositoryPort.TicketRecord("TKT-1", "t1", "u1", "工单1", "OPEN", 1000L);
        var ticket2 = new TicketRepositoryPort.TicketRecord("TKT-2", "t1", "u2", "工单2", "CLOSED", 2000L);
        var ticket3 = new TicketRepositoryPort.TicketRecord("TKT-3", "t2", "u1", "其他租户", "OPEN", 3000L);

        when(jedis.keys("agent:ticket:*")).thenReturn(Set.of(
                "agent:ticket:TKT-1",
                "agent:ticket:TKT-2",
                "agent:ticket:TKT-3"
        ));
        when(jedis.get("agent:ticket:TKT-1")).thenReturn(mapper.writeValueAsString(ticket1));
        when(jedis.get("agent:ticket:TKT-2")).thenReturn(mapper.writeValueAsString(ticket2));
        when(jedis.get("agent:ticket:TKT-3")).thenReturn(mapper.writeValueAsString(ticket3));

        var result = repo.findByTenant("t1");
        assertThat(result).hasSize(2);
        assertThat(result.stream().map(TicketRepositoryPort.TicketRecord::ticketId))
                .containsExactlyInAnyOrder("TKT-1", "TKT-2");
    }
}