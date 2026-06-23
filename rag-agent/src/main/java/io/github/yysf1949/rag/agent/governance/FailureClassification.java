package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
import io.github.yysf1949.rag.agent.exception.HandoffRequiredException;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import io.github.yysf1949.rag.core.exception.LlmUnavailableException;
import io.github.yysf1949.rag.core.exception.RerankUnavailableException;
import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Failure classification — maps an exception thrown by agent / tool /
 * RAG pipeline to a {@link Category}. Phase 32 R15 needs this to be
 * available before {@link FailureClassificationRouter} can route
 * anything. This enum was originally scoped in Phase 29 but the file
 * never landed; Phase 32 ships it as a prerequisite for R15.
 *
 * <p>Categories:</p>
 * <ul>
 *   <li>{@link Category#LIMITS} — provider rate-limited / quota exhausted / circuit breaker</li>
 *   <li>{@link Category#HALLUCINATION} — LLM returned off-topic/unsafe answer (detected downstream)</li>
 *   <li>{@link Category#TIMEOUT} — request exceeded time budget</li>
 *   <li>{@link Category#TOOL_ERROR} — tool raised an unexpected exception</li>
 *   <li>{@link Category#POLICY_DENY} — risk gate / authorization denial</li>
 * </ul>
 */
public final class FailureClassification {

    private FailureClassification() {
        // utility holder
    }

    public enum Category {
        LIMITS,
        HALLUCINATION,
        TIMEOUT,
        TOOL_ERROR,
        POLICY_DENY
    }

    /**
     * Best-effort classification of an exception into a {@link Category}.
     * Order matters: most specific type first, fall through to TOOL_ERROR
     * for everything else.
     *
     * @param ex the exception (must not be null)
     * @return non-null category; never returns null even for unknown types
     */
    public static Category classify(Throwable ex) {
        if (ex == null) {
            return Category.TOOL_ERROR;
        }

        // ── 1) Policy / authorization denials — explicit, check first ──
        if (ex instanceof ToolRiskDeniedException
                || ex instanceof AmountLimitExceededException) {
            return Category.POLICY_DENY;
        }
        // HandoffRequiredException is a business-rule handoff signal; treat
        // as POLICY_DENY because the user is being denied a self-service
        // path. The router will route it to handoff().
        if (ex instanceof HandoffRequiredException) {
            return Category.POLICY_DENY;
        }

        // ── 2) Limits / rate / circuit breaker ──
        if (ex instanceof HttpServerErrorException httpEx
                && httpEx.getStatusCode().value() == 429) {
            return Category.LIMITS;
        }
        // TenantRateLimitedException is local policy
        if (ex instanceof TenantRateLimitedException) {
            return Category.LIMITS;
        }
        // LLM/VectorStore/Rerank unavailable usually means the backend is
        // rate-limited or its circuit breaker is open.
        if (ex instanceof LlmUnavailableException
                || ex instanceof VectorStoreUnavailableException
                || ex instanceof RerankUnavailableException) {
            return Category.LIMITS;
        }
        // 5xx upstream
        if (ex instanceof HttpServerErrorException) {
            return Category.LIMITS;
        }

        // ── 3) Timeouts ──
        if (ex instanceof TimeoutException
                || ex instanceof SocketTimeoutException
                || ex instanceof ResourceAccessException) {
            return Category.TIMEOUT;
        }

        // ── 4) IO failures — treat as TOOL_ERROR unless wrapped timeout ──
        if (ex instanceof IOException) {
            return Category.TOOL_ERROR;
        }

        // ── 5) Default: generic tool error ──
        return Category.TOOL_ERROR;
    }
}
