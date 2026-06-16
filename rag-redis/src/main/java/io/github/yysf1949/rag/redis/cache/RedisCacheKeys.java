package io.github.yysf1949.rag.redis.cache;

/**
 * Cache key conventions for the rag-redis module. Spec §5.1.
 *
 * <pre>
 *   rag:answer-cache:{tenant}:{queryHash}      → JSON-serialised Answer
 *   rag:rewrite-cache:{tenant}:{queryHash}     → JSON-serialised RewriteResult
 *   rag:embedding-cache:{sha256(text)}         → FLOAT32 little-endian bytes
 * </pre>
 *
 * <p>Every key embeds the tenant id so cross-tenant lookups are structurally
 * impossible — a caller that forgets to pass tenantId gets a malformed key
 * rather than a hit.</p>
 *
 * <p>This class is stateless and side-effect-free.</p>
 */
public final class RedisCacheKeys {

    private RedisCacheKeys() {
    }

    public static String answerKey(String tenantId, String queryHash) {
        return "rag:answer-cache:" + req(tenantId, "tenantId") + ":" + req(queryHash, "queryHash");
    }

    public static String answerKeyPrefix(String tenantId) {
        return "rag:answer-cache:" + req(tenantId, "tenantId") + ":";
    }

    public static String rewriteKey(String tenantId, String queryHash) {
        return "rag:rewrite-cache:" + req(tenantId, "tenantId") + ":" + req(queryHash, "queryHash");
    }

    public static String rewriteKeyPrefix(String tenantId) {
        return "rag:rewrite-cache:" + req(tenantId, "tenantId") + ":";
    }

    public static String embeddingKey(String textHash) {
        return "rag:embedding-cache:" + req(textHash, "textHash");
    }

    private static String req(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
