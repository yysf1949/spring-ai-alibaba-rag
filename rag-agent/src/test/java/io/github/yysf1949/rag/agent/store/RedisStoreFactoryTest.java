package io.github.yysf1949.rag.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisStoreFactoryTest {

    @Test
    void buildsKeyWithPrefix() {
        var factory = new RedisStoreFactory(mock(JedisPooled.class), new ObjectMapper());
        assertThat(factory.key("order", "t1", "ORD-1"))
                .isEqualTo("agent:order:t1:ORD-1");
    }

    @Test
    void buildsKeyWithoutTenant() {
        var factory = new RedisStoreFactory(mock(JedisPooled.class), new ObjectMapper());
        assertThat(factory.key("ticket", "TKT-1"))
                .isEqualTo("agent:ticket:TKT-1");
    }
}
