package io.github.yysf1949.rag.core.port;

/**
 * LLM generation port — design spec §13.11.
 *
 * <p>The {@code QAService} calls {@link #generateAnswer(String, String)} with the
 * post-{@link ContextAssembler} prompt and expects a natural-language answer
 * (with {@code [N]} citation markers preserved verbatim).</p>
 *
 * <h2>Default impl</h2>
 * Production wires DashScope qwen-plus / qwen-max (spec §13.11). The dev /
 * test default is a stub that echoes the prompt — enough to exercise the
 * pipeline end-to-end without an API key.
 *
 * <h2>Error contract</h2>
 * <ul>
 *   <li><b>Never</b> return null — return an empty string or a "I cannot
 *       answer" placeholder so the {@code QAService} can decide whether to
 *       fall back to {@code FALLBACK_RULE}.</li>
 *   <li>Throw {@link io.github.yysf1949.rag.core.exception.LlmUnavailableException}
 *       on transient upstream failures (timeout, 5xx, rate limit). The
 *       {@code QAService} catches this and falls back to FALLBACK_RULE.</li>
 * </ul>
 */
public interface LlmService {

    /**
     * Generate an answer to {@code userQuery} given the assembled {@code prompt}.
     *
     * @param tenantId  for tenant-scoped prompt overrides / rate-limit quotas
     * @param prompt    the full prompt string (system + retrieved chunks + user question)
     * @return natural-language answer, never {@code null}
     * @throws io.github.yysf1949.rag.core.exception.LlmUnavailableException
     *         on transient upstream failure; the {@code QAService} catches
     *         this and falls back to {@code FALLBACK_RULE}.
     */
    String generateAnswer(String tenantId, String prompt);

    /**
     * Stable identifier of the underlying model — used as a label on traces
     * and metrics so operators can split dashboards by model version.
     */
    String modelId();
}
