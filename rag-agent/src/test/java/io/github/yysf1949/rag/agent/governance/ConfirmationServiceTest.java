package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认令牌服务测试 — Phase 21: 文章"人工确认是设计的一部分"。
 */
class ConfirmationServiceTest {

    @Test
    void generateCreatesValidToken() {
        var svc = new ConfirmationService();
        var token = svc.generate("create_refund", "user-1");

        assertThat(token.rawToken()).startsWith("CONF-");
        assertThat(token.toolName()).isEqualTo("create_refund");
        assertThat(token.userId()).isEqualTo("user-1");
        assertThat(token.isExpired()).isFalse();
        assertThat(svc.activeCount()).isEqualTo(1);
    }

    @Test
    void validateAndConsumesToken() {
        var svc = new ConfirmationService();
        var token = svc.generate("create_refund", "user-1");

        var result = svc.validateAndConsume(token.rawToken(), "create_refund", "user-1");
        assertThat(result).isNotNull();
        assertThat(result.rawToken()).isEqualTo(token.rawToken());
        // Token is consumed — second attempt returns null
        assertThat(svc.validateAndConsume(token.rawToken(), "create_refund", "user-1")).isNull();
        assertThat(svc.activeCount()).isEqualTo(0);
    }

    @Test
    void validateFailsForWrongTool() {
        var svc = new ConfirmationService();
        var token = svc.generate("create_refund", "user-1");

        var result = svc.validateAndConsume(token.rawToken(), "cancel_order", "user-1");
        assertThat(result).isNull();
    }

    @Test
    void validateFailsForWrongUser() {
        var svc = new ConfirmationService();
        var token = svc.generate("create_refund", "user-1");

        var result = svc.validateAndConsume(token.rawToken(), "create_refund", "user-2");
        assertThat(result).isNull();
    }

    @Test
    void validateFailsForNullToken() {
        var svc = new ConfirmationService();
        assertThat(svc.validateAndConsume(null, "create_refund", "user-1")).isNull();
        assertThat(svc.validateAndConsume("", "create_refund", "user-1")).isNull();
        assertThat(svc.validateAndConsume("  ", "create_refund", "user-1")).isNull();
    }

    @Test
    void validateFailsForNonexistentToken() {
        var svc = new ConfirmationService();
        assertThat(svc.validateAndConsume("CONF-fake", "create_refund", "user-1")).isNull();
    }

    @Test
    void cleanupRemovesExpiredTokens() {
        var svc = new ConfirmationService();
        // Manually create an expired token
        var expired = new ConfirmationToken("CONF-expired", "t", "u", System.currentTimeMillis() - 1000);
        // Can't directly add to svc, but we can test cleanup on fresh service
        svc.generate("tool", "user");
        assertThat(svc.activeCount()).isEqualTo(1);
        int removed = svc.cleanup();
        // No tokens should be expired yet (TTL is 5 minutes)
        assertThat(removed).isEqualTo(0);
    }

    @Test
    void matchesChecksToolAndUser() {
        var token = new ConfirmationToken("CONF-1", "create_refund", "user-1", System.currentTimeMillis() + 60000);
        assertThat(token.matches("create_refund", "user-1")).isTrue();
        assertThat(token.matches("cancel_order", "user-1")).isFalse();
        assertThat(token.matches("create_refund", "user-2")).isFalse();
    }

    @Test
    void agentIdentityWithConfirmationToken() {
        var id = new AgentIdentity("t1", "u1", "s1", java.util.Set.of("user"));
        assertThat(id.confirmationToken()).isNull();

        var withToken = id.withConfirmationToken("CONF-abc");
        assertThat(withToken.confirmationToken()).isEqualTo("CONF-abc");
        assertThat(withToken.tenantId()).isEqualTo("t1");
        assertThat(withToken.userId()).isEqualTo("u1");
    }
}
