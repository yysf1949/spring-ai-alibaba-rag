package io.github.yysf1949.rag.agent.governance;

/**
 * Strategy pattern for handling classified failures.
 * One method per category; router maps enum → method.
 *
 * <p>Phase 32 R15 — closes the loop between
 * {@link FailureClassification} and concrete recovery actions.</p>
 */
public interface FallbackStrategy {
    /** LIMITS — provider rate limited. Switch to backup provider. */
    void switchProvider(String reason);

    /** HALLUCINATION — LLM returned off-topic/unsafe answer. Use rule-based fallback. */
    void fallbackToRule(String reason);

    /** TIMEOUT — request exceeded budget. Retry with reduced scope. */
    void retry(String reason);

    /** TOOL_ERROR — tool raised an exception. Skip and continue. */
    void skip(String reason);

    /** POLICY_DENY — request denied by policy. Handoff to human. */
    void handoff(String reason);
}
