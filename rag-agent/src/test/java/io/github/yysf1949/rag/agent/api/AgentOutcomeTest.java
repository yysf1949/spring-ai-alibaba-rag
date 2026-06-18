package io.github.yysf1949.rag.agent.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentOutcomeTest {

    @Test
    void includesFiveStates() {
        assertThat(AgentOutcome.values())
                .containsExactly(
                        AgentOutcome.SUCCESS,
                        AgentOutcome.FAILURE,
                        AgentOutcome.DENIED,
                        AgentOutcome.REPLAY,
                        AgentOutcome.HANDOFF_REQUIRED);
    }

    @Test
    void successAndReplayAreTerminal() {
        assertThat(AgentOutcome.SUCCESS.isTerminal()).isTrue();
        assertThat(AgentOutcome.REPLAY.isTerminal()).isTrue();
        assertThat(AgentOutcome.FAILURE.isTerminal()).isTrue();
        assertThat(AgentOutcome.DENIED.isTerminal()).isTrue();
    }

    @Test
    void handoffRequiredIsNotTerminal() {
        // HANDOFF_REQUIRED 触发后续人工处理，Agent 自身不算终止
        assertThat(AgentOutcome.HANDOFF_REQUIRED.isTerminal()).isFalse();
    }

    @Test
    void parsesFromString() {
        assertThat(AgentOutcome.fromString("SUCCESS")).isEqualTo(AgentOutcome.SUCCESS);
        assertThat(AgentOutcome.fromString("handoff_required")).isEqualTo(AgentOutcome.HANDOFF_REQUIRED);
        assertThatThrownBy(() -> AgentOutcome.fromString("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN");
    }
}
