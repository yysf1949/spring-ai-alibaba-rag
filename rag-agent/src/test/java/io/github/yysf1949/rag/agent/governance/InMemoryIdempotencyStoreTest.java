package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.yysf1949.rag.agent.governance.IdempotencyStore.PutResult;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIdempotencyStoreTest {

    private InMemoryIdempotencyStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyStore();
    }

    @Test
    void firstPutReturnsFirst() {
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-1");
        PutResult result = store.putIfAbsent(key, "first-result");
        assertThat(result.isFirst()).isTrue();
        assertThat(result.value()).isEqualTo("first-result");
    }

    @Test
    void secondPutReturnsReplay() {
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-1");
        store.putIfAbsent(key, "first-result");
        PutResult result = store.putIfAbsent(key, "second-result");
        assertThat(result.isReplay()).isTrue();
        assertThat(result.value()).isEqualTo("first-result");
    }

    @Test
    void differentKeysAreIndependent() {
        var k1 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-1");
        var k2 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-2");
        assertThat(store.putIfAbsent(k1, "v1").isFirst()).isTrue();
        assertThat(store.putIfAbsent(k2, "v2").isFirst()).isTrue();
    }
}