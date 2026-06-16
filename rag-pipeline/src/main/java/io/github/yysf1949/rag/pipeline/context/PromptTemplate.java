package io.github.yysf1949.rag.pipeline.context;

import io.github.yysf1949.rag.core.model.Chunk;

/**
 * Strategy for rendering the prompt envelope around chunk bodies.
 *
 * <p>Spec §13.11 says "Prompt 模板 + 来源引用指令". The template owns:</p>
 * <ul>
 *   <li>How a single chunk is rendered (the {@code [N] header\nbody} shape).</li>
 *   <li>How the whole prompt is composed (system preamble + body + user
 *       question marker).</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe and side-effect-free — the
 * assembler calls them in a tight loop per chunk.</p>
 */
public interface PromptTemplate {

    /**
     * Render the header line for chunk {@code marker} (1-based).
     *
     * <p>The returned string MUST end with a newline so the body starts on
     * a fresh line.</p>
     *
     * <p>The header is what {@link io.github.yysf1949.rag.core.model.Citation}
     * carries back to the UI — keep {@code title}, {@code sectionPath},
     * {@code sourceUri} all visible (spec §13.10: "永不截断元数据").</p>
     */
    String header(int marker, Chunk chunk);

    /**
     * Wrap the body in the full prompt sent to the LLM.
     *
     * @param queryText  the user's question (post-rewrite)
     * @param body       concatenation of every {@link #header}-prefixed chunk
     * @return ready-to-send prompt string
     */
    String render(String queryText, String body);
}
