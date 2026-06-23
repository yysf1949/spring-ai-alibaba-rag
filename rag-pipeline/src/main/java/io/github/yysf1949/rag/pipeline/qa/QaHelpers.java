package io.github.yysf1949.rag.pipeline.qa;

import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.port.HotQuestionProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Static helper methods extracted from {@link QAServiceImpl} to reduce
 * the God-class surface. Pure functions — no state, no side effects.
 */
public final class QaHelpers {

    private QaHelpers() {}

    /**
     * SHA-256 hex of the <b>rewritten</b> text — two raw queries that
     * collapse to the same rewrite must share a cached answer.
     */
    public static String hashQuery(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase().replaceAll("\\s+", " ");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Step 7 fallback: concatenate retrieved chunks verbatim with citation markers. */
    public static String fallbackFromChunks(String queryText, List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("根据检索到的资料：\n");
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            sb.append("[").append(i + 1).append("] ");
            sb.append(safe(c.title())).append(" › ").append(safe(c.sectionPath())).append('\n');
            sb.append(c.content()).append('\n');
        }
        if (queryText != null && !queryText.isBlank()) {
            sb.append("\n（针对问题：").append(queryText).append("）");
        }
        return sb.toString();
    }

    /** Build a graceful "I don't know" answer with hot questions. */
    public static Answer emptyRetrievalAnswer(
            String tenantId, String queryHash, String rewrittenText,
            HotQuestionProvider hotQuestions, long t0) {
        List<String> hot = hotQuestions.recent(tenantId, 5);
        StringBuilder sb = new StringBuilder();
        sb.append("抱歉，知识库中没有找到与您问题相关的内容。");
        if (!hot.isEmpty()) {
            sb.append("\n\n您可以试试问：\n");
            for (String q : hot) {
                sb.append("• ").append(q).append('\n');
            }
        }
        return new Answer(
                tenantId,
                queryHash,
                rewrittenText,
                List.of(),
                List.of(),
                sb.toString(),
                List.of(),
                AnswerSource.FALLBACK_RULE,
                System.currentTimeMillis() - t0,
                java.util.Map.of("stage.retrieval.empty", true));
    }

    public static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Defensive: callers may pass an empty/negative topN — we floor it. */
    public static int safeTopN(int requested) {
        return requested > 0 ? requested : QAServiceImpl.DEFAULT_TOP_N;
    }

    /** Copy-and-truncate a list (used in fallback path). */
    public static <T> List<T> take(List<T> in, int n) {
        if (in == null || in.isEmpty()) return List.of();
        return new ArrayList<>(in.subList(0, Math.min(n, in.size())));
    }

    /** Build a permission-tag Set from varargs. */
    public static Set<String> tagSet(String... tags) {
        Set<String> out = new java.util.HashSet<>();
        for (String t : tags) {
            if (t != null && !t.isBlank()) out.add(t);
        }
        return out;
    }
}
