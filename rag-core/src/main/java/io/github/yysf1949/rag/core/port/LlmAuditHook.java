package io.github.yysf1949.rag.core.port;

/**
 * Side-effect port for LLM-call audit logging — design spec §21.
 *
 * <p>The {@code rag-pipeline} module is intentionally Spring-free so the
 * core orchestration logic is testable in isolation. But audit emission
 * (file appenders, Kafka producers, etc.) is a deployment concern that
 * lives in {@code rag-app}. We bridge the two with a port: the pipeline
 * invokes {@link #onLlmCall}, rag-app injects a Spring-managed adapter
 * that delegates to {@code AuditChannel}.</p>
 *
 * <p>The contract is intentionally narrow:</p>
 * <ul>
 *   <li><b>Never throws</b> — a failed audit MUST NOT abort the LLM
 *       call path. The implementation absorbs errors and tracks them
 *       on the {@code rag.audit.errors.total} gauge.</li>
 *   <li><b>Never blocks</b> — implementations are expected to be
 *       non-blocking. For high-throughput deployments the rag-app
 *       adapter can hand off to an {@code AsyncAppender}.</li>
 * </ul>
 *
 * <p>The default no-op implementation lets rag-pipeline run in tests
 * and in deployments that haven't wired the rag-app adapter yet —
 * audit is opt-in, not load-bearing.</p>
 */
@FunctionalInterface
public interface LlmAuditHook {

    /**
     * Record that the LLM was called with the given prompt and returned
     * the given completion. Callers MUST invoke this on the success path
     * AND the degradation path (FALLBACK_RULE) so the audit log captures
     * the model + prompt + response triple regardless of outcome.
     *
     * @param tenantId    the tenant context (never null)
     * @param userId      the end user (may be null for system calls)
     * @param sessionId   the chat session (may be null)
     * @param queryHash   SHA-256 of the rewritten query (never null — see
     *                    QAServiceImpl#hashQuery)
     * @param modelId     the actual model that produced the response
     *                    (e.g. "Qwen/Qwen2.5-7B-Instruct", "stub-echo")
     * @param promptTemplate the prompt template id (e.g. "qa-default",
     *                    "qa-summary") — for prompt-version correlation
     * @param promptBody  the rendered prompt (truncated by the caller
     *                    if too large; implementations do not re-truncate)
     * @param completion  the LLM's raw text response (never null)
     * @param latencyMs   wall-clock time of the LLM call
     * @param outcome     "SUCCESS" / "FAILURE" / "DEGRADED" — coarse
     */
    void onLlmCall(
            String tenantId,
            String userId,
            String sessionId,
            String queryHash,
            String modelId,
            String promptTemplate,
            String promptBody,
            String completion,
            long latencyMs,
            String outcome);

    /** No-op default — audit is opt-in, not load-bearing. */
    LlmAuditHook NOOP = (tenantId, userId, sessionId, queryHash, modelId,
            promptTemplate, promptBody, completion, latencyMs, outcome) -> { };
}
