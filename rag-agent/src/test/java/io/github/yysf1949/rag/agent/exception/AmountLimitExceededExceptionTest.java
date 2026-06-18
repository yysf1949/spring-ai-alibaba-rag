package io.github.yysf1949.rag.agent.exception;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AmountLimitExceededExceptionTest {

    @Test
    void carriesToolNameAndLimits() {
        AmountLimitExceededException e = new AmountLimitExceededException(
                "create_refund", 50000L, 10000L);
        assertThat(e.getMessage())
                .contains("create_refund")
                .contains("50000")
                .contains("10000");
        assertThat(e.toolName()).isEqualTo("create_refund");
        assertThat(e.requestedCents()).isEqualTo(50000L);
        assertThat(e.limitCents()).isEqualTo(10000L);
    }
}
