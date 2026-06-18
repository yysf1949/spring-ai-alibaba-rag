package io.github.yysf1949.rag.agent.builtin;

import java.util.Map;

/**
 * Phase 18 P0 — {@link KbSearchResponse} 内单条 chunk, 拆成顶层 record.
 *
 * <p>见 {@link KbSearchRequest} javadoc 解释为什么要拆顶层.</p>
 */
public record KbSearchChunk(
        String id,
        String text,
        double score,
        String kbId,
        long kbVersion,
        Map<String, String> metadata
) {}