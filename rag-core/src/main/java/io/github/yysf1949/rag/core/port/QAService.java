package io.github.yysf1949.rag.core.port;

import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.Query;

/**
 * The full online QA chain — design spec §7.1 + §13.11.
 *
 * <h2>Standard pipeline (spec §7.1)</h2>
 * <pre>
 *   Query → AnswerCache (hit?)
 *     → [hit] return Answer(SOURCE=CACHE)
 *     → [miss] RewriteService
 *          → EmbeddingCache / EmbeddingGateway
 *          → VectorStore.search (filtered: tenantId, kbId, kbVersion,
 *                                 status=ACTIVE, permissionTags ⊆ user)
 *          → RerankService (TopK → TopN=5)
 *          → ContextAssembler (token 预算)
 *          → LlmService.generate(prompt)
 *          → write AnswerCache
 *          → return Answer(SOURCE=LLM)
 * </pre>
 *
 * <h2>Degradation ladder (spec §7.5)</h2>
 * <ul>
 *   <li><b>Cache hit</b> — short-circuit, return {@link io.github.yysf1949.rag.core.model.AnswerSource#CACHE}.</li>
 *   <li><b>Rerank fails</b> — skip rerank, use vector search's TopK directly
 *       (truncated to TopN=min(TopK, 5)). Answer still goes through the LLM.</li>
 *   <li><b>LLM fails/times out</b> — concatenate retrieved chunks verbatim
 *       and return {@link io.github.yysf1949.rag.core.model.AnswerSource#FALLBACK_RULE}.</li>
 *   <li><b>Retrieval returns empty</b> — return a "I don't know" placeholder
 *       with a list of recent hot questions from
 *       {@link HotQuestionProvider}, still tagged FALLBACK_RULE.</li>
 *   <li><b>VectorStore down</b> — propagate the exception to the caller.
 *       The HTTP layer translates this to 503 + Retry-After (spec §10).</li>
 * </ul>
 */
public interface QAService {

    /**
     * Run the full QA pipeline for {@code query}.
     *
     * @param query caller-side context (tenantId, userId, sessionId, rawText,
     *              permissionTags, topK, kbVersion).
     * @return a fully populated {@link Answer} — never {@code null}.
     *         The {@code source} field records which leg produced the text.
     */
    Answer answer(Query query);
}
