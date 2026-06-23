package io.github.yysf1949.rag.agent.builtin;

import java.util.List;

/**
 * Phase 18 P0 — {@link KbSearchTool} 出参 record, 拆成顶层类.
 *
 * <p>LLM 拿到的 JSON 形态 (Plan §2.4):</p>
 * <pre>{@code
 * {
 *   "kbId": "default",
 *   "query": "退款政策",
 *   "total": 3,
 *   "chunks": [
 *     {"id":"c-1","text":"...","score":0.92,"kbId":"default","kbVersion":42,"metadata":{...}}
 *   ]
 * }
 * }</pre>
 *
 * <p>见 {@link KbSearchRequest} javadoc 解释为什么要拆顶层.</p>
 */
public record KbSearchResponse(
        String kbId,
        String query,
        int total,
        List<KbSearchChunk> chunks
) {}