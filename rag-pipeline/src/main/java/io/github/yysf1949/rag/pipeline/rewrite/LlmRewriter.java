package io.github.yysf1949.rag.pipeline.rewrite;

import io.github.yysf1949.rag.core.exception.LlmUnavailableException;
import io.github.yysf1949.rag.core.port.RewriteService.RewriteResult;

/**
 * Stub for the LLM-based rewriter leg — design spec §11.2. Production
 * implementations call DashScope qwen-plus (or similar) to rewrite the
 * query when {@link RuleBasedQueryRewriter}'s confidence falls below
 * {@link RuleBasedQueryRewriter#llmFallbackThreshold()}.
 *
 * <p>The interface exists <b>now</b> so the rule-based rewriter can be
 * wired with a stub during dev (no key needed) and swapped to a real
 * DashScope client later without touching call sites.</p>
 *
 * <h2>Why this is a port, not a static class</h2>
 * Same reason as {@link io.github.yysf1949.rag.core.port.RewriteService} —
 * keeps the pipeline testable without a live LLM endpoint, and lets the
 * caller choose between DashScope / OpenAI / Anthropic / a local model
 * via Spring configuration.
 *
 * <h2>Error contract</h2>
 * <b>Never</b> throw on a transient upstream failure — return
 * {@code null} (or caller's input unchanged). The rule-based leg already
 * produced a candidate; we only call LLM to upgrade quality, so a missed
 * call should silently fall back to the rule output, not blow up the QA
 * chain. If you really can't recover, throw {@link LlmUnavailableException}
 * so the upper layer can mark the request {@code FALLBACK_RULE}.
 */
public interface LlmRewriter {

    /**
     * Rewrite {@code rawText} via an LLM. {@code tenantId} is included so
     * per-tenant prompt customization is possible (e.g. one tenant prefers
     * formal style, another prefers terse).
     *
     * @param tenantId  for tenant-scoped prompt overrides
     * @param rawText   the post-rule-stage input
     * @return rewritten text + a confidence score, or {@code null} if the
     *         LLM call failed and the caller should keep the rule result.
     */
    RewriteResult rewrite(String tenantId, String rawText);
}
