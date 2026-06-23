package io.github.yysf1949.rag.agent.action;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskLevelTest {

    @Test
    void parseL1Read() {
        assertThat(RiskLevel.parse("L1_READ")).isEqualTo(RiskLevel.L1_READ);
        assertThat(RiskLevel.parse("l1_read")).isEqualTo(RiskLevel.L1_READ);
    }

    @Test
    void parseL4HighRisk() {
        assertThat(RiskLevel.parse("L4_HIGH_RISK")).isEqualTo(RiskLevel.L4_HIGH_RISK);
    }

    @Test
    void parseUnknownThrows() {
        assertThatThrownBy(() -> RiskLevel.parse("L5_FOO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L5_FOO");
    }

    @Test
    void requiresConfirmationFromL3() {
        assertThat(RiskLevel.L1_READ.requiresConfirmation()).isFalse();
        assertThat(RiskLevel.L2_REVERSIBLE.requiresConfirmation()).isFalse();
        assertThat(RiskLevel.L3_BUSINESS_STATE.requiresConfirmation()).isTrue();
        assertThat(RiskLevel.L4_HIGH_RISK.requiresConfirmation()).isTrue();
    }

    @Test
    void ordersMonotonically() {
        assertThat(RiskLevel.L1_READ.ordinal()).isLessThan(RiskLevel.L2_REVERSIBLE.ordinal());
        assertThat(RiskLevel.L2_REVERSIBLE.ordinal()).isLessThan(RiskLevel.L3_BUSINESS_STATE.ordinal());
        assertThat(RiskLevel.L3_BUSINESS_STATE.ordinal()).isLessThan(RiskLevel.L4_HIGH_RISK.ordinal());
    }
}