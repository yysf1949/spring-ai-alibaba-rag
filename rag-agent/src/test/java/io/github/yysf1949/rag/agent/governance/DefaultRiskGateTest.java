package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRiskGateTest {

    static class FakeBean {
        public record Input(String x) {}
        public record Output(String y) {}

        public Output run(Input i) { return new Output(i.x() + "!"); }
    }

    private ToolDescriptor desc(RiskLevel level) throws NoSuchMethodException {
        Method m = FakeBean.class.getMethod("run", FakeBean.Input.class);
        return new ToolDescriptor("t", "d", level, true, false, null, false, new FakeBean(), m);
    }

    @Test
    void l1ReadAlwaysAllowed() throws Exception {
        var gate = new DefaultRiskGate(new ConfirmationService());
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        assertThatCode(() -> gate.check(desc(RiskLevel.L1_READ), identity, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void l3RequiresIdempotencyKey() throws Exception {
        var gate = new DefaultRiskGate(new ConfirmationService());
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        assertThatThrownBy(() -> gate.check(desc(RiskLevel.L3_BUSINESS_STATE), identity, null, null))
                .isInstanceOf(ToolRiskDeniedException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void l4RequiresAdminRole() throws Exception {
        var gate = new DefaultRiskGate(new ConfirmationService());
        var normalUser = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "dangerous", "tok-1");
        assertThatThrownBy(() -> gate.check(desc(RiskLevel.L4_HIGH_RISK), normalUser, key, null))
                .isInstanceOf(ToolRiskDeniedException.class)
                .hasMessageContaining("admin");

        var adminUser = new AgentIdentity("t1", "u1", "s1", Set.of("admin"));
        assertThatCode(() -> gate.check(desc(RiskLevel.L4_HIGH_RISK), adminUser, key, null))
                .doesNotThrowAnyException();
    }

    @Test
    void toolRequiresKeyButMissingFails() throws Exception {
        var gate = new DefaultRiskGate(new ConfirmationService());
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        Method m = FakeBean.class.getMethod("run", FakeBean.Input.class);
        // requiresIdempotencyKey=true
        var tool = new ToolDescriptor("t2", "d", RiskLevel.L2_REVERSIBLE, false, true, null, false, new FakeBean(), m);
        assertThatThrownBy(() -> gate.check(tool, identity, null, null))
                .isInstanceOf(ToolRiskDeniedException.class);
    }

    // ─── Phase 10 新增: L3 金额门控 + L4 admin 校验 ──────────────────────

    private static AgentIdentity identity(String userId, String tenantId, String sessionId, List<String> roles) {
        return new AgentIdentity(tenantId, userId, sessionId, Set.copyOf(roles));
    }

    private static Method getAnyMethod() throws NoSuchMethodException {
        return FakeBean.class.getMethod("run", FakeBean.Input.class);
    }

    @Test
    void l3WithAmountUnderLimitPasses() throws Exception {
        var gate = new DefaultRiskGate(new ConfirmationService());
        ToolDescriptor desc = new ToolDescriptor(
                "create_refund", "create refund", RiskLevel.L3_BUSINESS_STATE,
                true, true, 100_00L, false, // maxAmountCents = 100 元
                new Object(), getAnyMethod());
        IdempotencyKey key = IdempotencyKey.of("tenant-1", "user-1", "session-1", "create_refund", "refund-1");
        // 50 元（5000 cents）不超限
        gate.check(desc, identity("user-1", "tenant-1", "session-1", List.of()), key, 50_00L);
    }

    @Test
    void l3WithAmountOverLimitThrowsHandoff() throws Exception {
        var gate = new DefaultRiskGate(new ConfirmationService());
        ToolDescriptor desc = new ToolDescriptor(
                "create_refund", "create refund", RiskLevel.L3_BUSINESS_STATE,
                true, true, 100_00L, false, // maxAmountCents = 100 元
                new Object(), getAnyMethod());
        IdempotencyKey key = IdempotencyKey.of("tenant-1", "user-1", "session-1", "create_refund", "refund-1");
        // 500 元（50000 cents）超限
        assertThatThrownBy(() ->
                gate.check(desc, identity("user-1", "tenant-1", "session-1", List.of()), key, 500_00L))
                .isInstanceOf(AmountLimitExceededException.class)
                .hasMessageContaining("50000")
                .hasMessageContaining("10000");
    }

    @Test
    void l4WithoutAdminRoleThrowsDenied() throws Exception {
        var gate = new DefaultRiskGate(new ConfirmationService());
        ToolDescriptor desc = new ToolDescriptor(
                "direct_refund", "direct refund (admin)", RiskLevel.L4_HIGH_RISK,
                true, true, null, false,
                new Object(), getAnyMethod());
        IdempotencyKey key = IdempotencyKey.of("tenant-1", "user-1", "session-1", "direct_refund", "refund-admin-1");
        // 普通用户被拒
        assertThatThrownBy(() ->
                gate.check(desc, identity("user-1", "tenant-1", "session-1", List.of("user")), key, null))
                .isInstanceOf(ToolRiskDeniedException.class)
                .hasMessageContaining("L4_HIGH_RISK");
    }

    @Test
    void l4WithAdminRolePasses() throws Exception {
        var gate = new DefaultRiskGate(new ConfirmationService());
        ToolDescriptor desc = new ToolDescriptor(
                "direct_refund", "direct refund (admin)", RiskLevel.L4_HIGH_RISK,
                true, true, null, false,
                new Object(), getAnyMethod());
        IdempotencyKey key = IdempotencyKey.of("tenant-1", "admin-1", "session-1", "direct_refund", "refund-admin-1");
        gate.check(desc, identity("admin-1", "tenant-1", "session-1", List.of("admin")), key, null);
    }
}