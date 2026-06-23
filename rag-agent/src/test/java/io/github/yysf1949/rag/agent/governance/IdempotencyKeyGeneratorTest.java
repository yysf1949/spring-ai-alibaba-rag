package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语义化幂等键生成器测试 — 3 个用例对齐文章"幂等键 = 业务意图 + 业务对象"原话。
 */
class IdempotencyKeyGeneratorTest {

    private static AgentIdentity identity(String sessionId) {
        return new AgentIdentity("tenant-A", "user-1", sessionId, Set.of("user"));
    }

    @Test
    void sameSessionSameActionSameObjectSameKey() {
        // 同一会话、同一业务对象、同一确认动作 → 同 key（重复点击安全）
        var k1 = IdempotencyKeyGenerator.forCancelOrder(identity("s-1"), "O-100", "btn-click-1234");
        var k2 = IdempotencyKeyGenerator.forCancelOrder(identity("s-1"), "O-100", "btn-click-1234");
        assertThat(k1).isEqualTo(k2);
        assertThat(k1.toolName()).isEqualTo("cancel_order");
        assertThat(k1.rawToken()).contains("cancelOrder:O-100:btn-click-1234");
    }

    @Test
    void differentSessionsProduceDifferentKeys() {
        // 不同会话 → 不同 key（即使业务对象相同也不行，避免会话 A 重放会话 B）
        var k1 = IdempotencyKeyGenerator.forCancelOrder(identity("s-1"), "O-100", "btn-click-1234");
        var k2 = IdempotencyKeyGenerator.forCancelOrder(identity("s-2"), "O-100", "btn-click-1234");
        assertThat(k1).isNotEqualTo(k2);
        assertThat(k1.sessionId()).isEqualTo("s-1");
        assertThat(k2.sessionId()).isEqualTo("s-2");
    }

    @Test
    void differentActionsProduceDifferentKeys() {
        // 同会话 + 同业务对象 + 同按钮 + 不同 action → 不同 key
        // （如：取消订单 vs 退款不应该共用 key）
        var cancel = IdempotencyKeyGenerator.forCancelOrder(identity("s-1"), "O-100", "btn-1");
        var refund = IdempotencyKeyGenerator.forCreateRefund(identity("s-1"), "O-100", "买错了", "btn-1");
        assertThat(cancel).isNotEqualTo(refund);
        assertThat(cancel.toolName()).isEqualTo("cancel_order");
        assertThat(refund.toolName()).isEqualTo("create_refund");
    }

    @Test
    void stableTokenBlankGeneratesUuidFallback() {
        // 调用方没传 stableToken 时，回退 UUID — 仍然产生稳定 key
        var k1 = IdempotencyKeyGenerator.forCancelOrder(identity("s-1"), "O-100", "");
        var k2 = IdempotencyKeyGenerator.forCancelOrder(identity("s-1"), "O-100", null);
        // 两次调用都生成了不同 UUID，key 不同（但都合法）
        assertThat(k1).isNotEqualTo(k2);
        assertThat(k1.rawToken()).startsWith("cancelOrder:O-100:");
    }
}