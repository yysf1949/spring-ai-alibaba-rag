package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.governance.FailureClassification.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Routes FailureClassification categories to fallback strategies.
 * Phase 32 R15 — completes the failure-handling loop.
 */
class FailureClassificationRouterTest {

    private FallbackStrategy strategy;
    private FailureClassificationRouter router;

    @BeforeEach
    void setUp() {
        strategy = Mockito.mock(FallbackStrategy.class);
        router = new FailureClassificationRouter(strategy);
    }

    @Test
    void limits_routesToSwitchProvider() {
        boolean handled = router.route(Category.LIMITS, "rate limited");
        assertThat(handled).isTrue();
        verify(strategy, times(1)).switchProvider("rate limited");
    }

    @Test
    void hallucination_routesToFallbackToRule() {
        boolean handled = router.route(Category.HALLUCINATION, "llm returned off-topic answer");
        assertThat(handled).isTrue();
        verify(strategy, times(1)).fallbackToRule("llm returned off-topic answer");
    }

    @Test
    void timeout_routesToRetry() {
        boolean handled = router.route(Category.TIMEOUT, "30s elapsed");
        assertThat(handled).isTrue();
        verify(strategy, times(1)).retry("30s elapsed");
    }

    @Test
    void toolError_routesToSkip() {
        boolean handled = router.route(Category.TOOL_ERROR, "tool X not found");
        assertThat(handled).isTrue();
        verify(strategy, times(1)).skip("tool X not found");
    }

    @Test
    void policyDeny_routesToHandoff() {
        boolean handled = router.route(Category.POLICY_DENY, "user not authorized");
        assertThat(handled).isTrue();
        verify(strategy, times(1)).handoff("user not authorized");
    }

    @Test
    void nullCategory_returnsFalseAndDoesNotCallStrategy() {
        boolean handled = router.route(null, "edge case");
        assertThat(handled).isFalse();
        verify(strategy, never()).switchProvider(any());
        verify(strategy, never()).fallbackToRule(any());
        verify(strategy, never()).retry(any());
        verify(strategy, never()).skip(any());
        verify(strategy, never()).handoff(any());
    }
}
