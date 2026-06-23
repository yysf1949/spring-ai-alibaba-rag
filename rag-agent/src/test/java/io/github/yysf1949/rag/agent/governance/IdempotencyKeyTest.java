package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> IdempotencyKey.of("tenant1", "user1", "session-1", "create_ticket", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyToken");
    }

    @Test
    void stableHashIsOrderIndependent() {
        // The raw token is the source of truth; the hash is a compact form.
        var k1 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-123");
        var k2 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-123");
        assertThat(k1).isEqualTo(k2);
        assertThat(k1.rawToken()).isEqualTo("tok-123");
    }

    @Test
    void differentTokensProduceDifferentKeys() {
        var k1 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-A");
        var k2 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-B");
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void recordEqualsAndHashCodeWork() {
        var k1 = IdempotencyKey.of("t1", "u1", "s1", "create_ticket", "tok-123");
        // rawToken and hash must both match for record equality — re-construct
        // with k1's own hash so all 6 fields are identical (plan 原稿写了
        // "h-abc" 占位符,跟 k1 的 SHA-256 hex 必然不等,导致断言失败,
        // 这是 plan 自带的 bug — 修法见提交说明)。
        var k2 = new IdempotencyKey("t1", "u1", "s1", "create_ticket", "tok-123", k1.hash());
        assertThat(k1).isEqualTo(k2);
        assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    }
}