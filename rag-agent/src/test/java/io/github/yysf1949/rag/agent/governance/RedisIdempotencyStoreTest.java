package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisIdempotencyStoreTest {

    private JedisPool pool;
    private Jedis jedis;
    private RedisIdempotencyStore store;

    private static IdempotencyKey key(String token) {
        return IdempotencyKey.of("tenant", "user", "session", "tool", token);
    }

    @BeforeEach
    void setUp() {
        pool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        store = new RedisIdempotencyStore(pool, "test:idemp:");
    }

    @Test
    void putIfAbsentFirstTimeSetsKey() {
        // SET 返回 "OK" = 新建
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn("OK");

        var result = store.putIfAbsent(key("k1"), "v1");
        assertThat(result.isFirst()).isTrue();
        assertThat(result.value()).isEqualTo("v1");

        // 验证: SETNX + EX 调过
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SetParams> paramCap = ArgumentCaptor.forClass(SetParams.class);
        verify(jedis).set(keyCap.capture(), anyString(), paramCap.capture());
        assertThat(keyCap.getValue()).startsWith("test:idemp:");
    }

    @Test
    void putIfAbsentReplayReturnsExisting() {
        // SET 返回 null = 键已存在
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn(null);
        // GET 返回之前存的值
        when(jedis.get(anyString())).thenReturn("existing-value");

        var result = store.putIfAbsent(key("k1"), "v1");
        assertThat(result.isReplay()).isTrue();
        assertThat(result.value()).isEqualTo("existing-value");
    }

    @Test
    void replaceOverridesValue() {
        // 普通 SET, 不带 NX
        when(jedis.set(anyString(), anyString())).thenReturn("OK");

        store.replace(key("k1"), "v1");

        verify(jedis).set(anyString(), anyString());
    }

    @Test
    void closeReturnsJedisToPool() {
        store.close();
        verify(jedis).close();
    }
}