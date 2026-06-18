package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
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
        return new ToolDescriptor("t", "d", level, true, false, new FakeBean(), m);
    }

    @Test
    void l1ReadAlwaysAllowed() throws Exception {
        var gate = new DefaultRiskGate();
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        assertThatCode(() -> gate.check(desc(RiskLevel.L1_READ), identity, null))
                .doesNotThrowAnyException();
    }

    @Test
    void l3RequiresIdempotencyKey() throws Exception {
        var gate = new DefaultRiskGate();
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        assertThatThrownBy(() -> gate.check(desc(RiskLevel.L3_BUSINESS_STATE), identity, null))
                .isInstanceOf(ToolRiskDeniedException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void l4RequiresAdminRole() throws Exception {
        var gate = new DefaultRiskGate();
        var normalUser = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "dangerous", "tok-1");
        assertThatThrownBy(() -> gate.check(desc(RiskLevel.L4_HIGH_RISK), normalUser, key))
                .isInstanceOf(ToolRiskDeniedException.class)
                .hasMessageContaining("admin");

        var adminUser = new AgentIdentity("t1", "u1", "s1", Set.of("admin"));
        assertThatCode(() -> gate.check(desc(RiskLevel.L4_HIGH_RISK), adminUser, key))
                .doesNotThrowAnyException();
    }

    @Test
    void toolRequiresKeyButMissingFails() throws Exception {
        var gate = new DefaultRiskGate();
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        Method m = FakeBean.class.getMethod("run", FakeBean.Input.class);
        // requiresIdempotencyKey=true
        var tool = new ToolDescriptor("t2", "d", RiskLevel.L2_REVERSIBLE, false, true, new FakeBean(), m);
        assertThatThrownBy(() -> gate.check(tool, identity, null))
                .isInstanceOf(ToolRiskDeniedException.class);
    }
}
