package io.github.yysf1949.rag.redis.cache;

import io.github.yysf1949.rag.core.port.RewriteCache;
import io.github.yysf1949.rag.core.port.RewriteService.RewriteResult;
import io.github.yysf1949.rag.redis.config.RedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Redis-backed {@link RewriteCache} — design spec §11.2 + §13.8.
 *
 * <p>Stores {@link RewriteResult} as JSON at
 * {@code rag:rewrite-cache:{tenant}:{queryHash}}. TTL is intentionally
 * shorter than the answer cache (default 6h, spec §13.8) because rewrite
 * rules / synonym tables tend to evolve more often than the underlying
 * knowledge base.</p>
 *
 * <p>Read failures degrade to a cache miss; the QA chain then re-runs the
 * rule pass, which is fast. Write failures are logged at warn and dropped —
 * a missing rewrite cache entry only costs one extra rule pass.</p>
 */
public class RedisRewriteCache implements RewriteCache {

    private static final Logger log = LoggerFactory.getLogger(RedisRewriteCache.class);

    /** Default TTL — spec §13.8 default. */
    public static final long DEFAULT_TTL_SECONDS = 6 * 60 * 60L;

    private final RedisConnection connection;
    private final long ttlSeconds;

    private final ConcurrentHashMap<String, LongAdder> hits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> misses = new ConcurrentHashMap<>();

    public RedisRewriteCache(RedisConnection connection) {
        this(connection, DEFAULT_TTL_SECONDS);
    }

    public RedisRewriteCache(RedisConnection connection, long ttlSeconds) {
        this.connection = connection;
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be > 0, got " + ttlSeconds);
        }
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public RewriteResult get(String tenantId, String queryHash) {
        String key = RedisCacheKeys.rewriteKey(tenantId, queryHash);
        try {
            byte[] blob = connection.client().get(key.getBytes(StandardCharsets.UTF_8));
            if (blob == null || blob.length == 0) {
                recordMiss(tenantId);
                return null;
            }
            RewriteResult r = JacksonHolder.get().readValue(blob, RewriteResult.class);
            recordHit(tenantId);
            return r;
        } catch (Exception e) {
            log.warn("RewriteCache.get miss-as-failure tenant={} err={}", tenantId, e.getMessage());
            recordMiss(tenantId);
            return null;
        }
    }

    @Override
    public boolean put(String tenantId, String queryHash, RewriteResult result) {
        if (result == null) return false;
        String key = RedisCacheKeys.rewriteKey(tenantId, queryHash);
        try {
            byte[] payload = JacksonHolder.get().writeValueAsBytes(result);
            UnifiedJedis client = connection.client();
            String resp = client.set(
                    key.getBytes(StandardCharsets.UTF_8),
                    payload,
                    SetParams.setParams().ex(ttlSeconds));
            return "OK".equals(resp);
        } catch (Exception e) {
            log.warn("RewriteCache.put failure tenant={} hash={} err={}",
                    tenantId, queryHash, e.getMessage());
            return false;
        }
    }

    @Override
    public double hitRatio(String tenantId) {
        LongAdder h = hits.get(tenantId);
        LongAdder m = misses.get(tenantId);
        long hits = h == null ? 0L : h.sum();
        long misses = m == null ? 0L : m.sum();
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    private void recordHit(String tenantId) {
        hits.computeIfAbsent(tenantId, k -> new LongAdder()).increment();
    }

    private void recordMiss(String tenantId) {
        misses.computeIfAbsent(tenantId, k -> new LongAdder()).increment();
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }
}
