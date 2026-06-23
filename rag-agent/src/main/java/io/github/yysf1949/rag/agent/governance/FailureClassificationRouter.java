package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.governance.FailureClassification.Category;
import org.springframework.stereotype.Component;

/**
 * Routes {@link FailureClassification} categories to {@link FallbackStrategy}
 * methods. Thread-safe and stateless. Test seam: constructor takes a
 * {@link FallbackStrategy} so unit tests can verify routing without
 * touching the default handler.
 *
 * <p>Phase 32 R15 — completes the failure-classification → action loop
 * that {@link FailureClassification} started.</p>
 */
@Component
public class FailureClassificationRouter {

    private final FallbackStrategy strategy;

    public FailureClassificationRouter(FallbackStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * Route a classified failure to the appropriate fallback.
     *
     * @param category classification result (may be null)
     * @param reason   human-readable reason; passed through to the strategy
     * @return true if routed; false if category is null (caller should log)
     */
    public boolean route(Category category, String reason) {
        if (category == null) {
            return false;
        }
        return switch (category) {
            case LIMITS -> {
                strategy.switchProvider(reason);
                yield true;
            }
            case HALLUCINATION -> {
                strategy.fallbackToRule(reason);
                yield true;
            }
            case TIMEOUT -> {
                strategy.retry(reason);
                yield true;
            }
            case TOOL_ERROR -> {
                strategy.skip(reason);
                yield true;
            }
            case POLICY_DENY -> {
                strategy.handoff(reason);
                yield true;
            }
        };
    }
}
