package io.github.yysf1949.rag.redis.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.yysf1949.rag.core.exception.CacheUnavailableException;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.port.AnswerCache;
import io.github.yysf1949.rag.redis.config.RedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Redis-backed {@link AnswerCache} — design spec §7.1 + §13.6.
 *
 * <p>Each {@link Answer} is JSON-encoded with Jackson and stored at
 * {@code rag:answer-cache:{tenant}:{queryHash}} with a configurable TTL.
 * On read miss / parse failure / backend error we return
 * {@link Optional#empty()} (never throw) so the QA chain can fall back to
 * the slow path gracefully.</p>
 *
 * <p>{@link #invalidateTenant(String)} uses {@code SCAN} + {@code DEL} in
 * batches; on Redis 6.2+ this can be replaced with {@code UNLINK} for
 * non-blocking semantics.</p>
 */
public class RedisAnswerCache implements AnswerCache {

    private static final Logger log = LoggerFactory.getLogger(RedisAnswerCache.class);

    /** Default TTL — spec §13.6 default. */
    public static final long DEFAULT_TTL_SECONDS = 24 * 60 * 60L;

    /** Maximum keys to delete per SCAN batch. */
    private static final int SCAN_BATCH = 500;

    private final RedisConnection connection;
    private final long ttlSeconds;

    /** Per-tenant hit / miss counters for {@link #hitRatio(String)}. */
    private final ConcurrentHashMap<String, LongAdder> hits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> misses = new ConcurrentHashMap<>();

    public RedisAnswerCache(RedisConnection connection) {
        this(connection, DEFAULT_TTL_SECONDS);
    }

    public RedisAnswerCache(RedisConnection connection, long ttlSeconds) {
        this.connection = connection;
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be > 0, got " + ttlSeconds);
        }
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public Optional<Answer> get(String tenantId, String queryHash) {
        String key = RedisCacheKeys.answerKey(tenantId, queryHash);
        try {
            String json = connection.client().get(key);
            if (json == null) {
                recordMiss(tenantId);
                return Optional.empty();
            }
            Answer answer = JacksonHolder.get().readValue(json, Answer.class);
            recordHit(tenantId);
            return Optional.of(answer);
        } catch (Exception e) {
            // Treat any backend or parse failure as a miss so the QA chain
            // can fall back to the slow path. Logged at warn so it's
            // observable in production without flooding ERROR.
            log.warn("AnswerCache.get miss-as-failure tenant={} err={}", tenantId, e.getMessage());
            recordMiss(tenantId);
            return Optional.empty();
        }
    }

    @Override
    public boolean put(String tenantId, String queryHash, Answer answer) {
        if (answer == null) return false;
        String key = RedisCacheKeys.answerKey(tenantId, queryHash);
        try {
            byte[] payload = JacksonHolder.get().writeValueAsBytes(answer);
            String resp = connection.client().set(
                    key.getBytes(StandardCharsets.UTF_8),
                    payload,
                    SetParams.setParams().ex(ttlSeconds));
            return "OK".equals(resp);
        } catch (JsonProcessingException jpe) {
            log.warn("AnswerCache.put serialize-failure tenant={} hash={} err={}",
                    tenantId, queryHash, jpe.getMessage());
            return false;
        } catch (Exception e) {
            throw new CacheUnavailableException("AnswerCache.put failed for tenant=" + tenantId, e);
        }
    }

    @Override
    public long invalidateTenant(String tenantId) {
        String prefix = RedisCacheKeys.answerKeyPrefix(tenantId);
        long deleted = 0;
        UnifiedJedis client = connection.client();
        String cursor = "0";
        try {
            do {
                var scan = client.scan(cursor,
                        new redis.clients.jedis.params.ScanParams().match(prefix + "*").count(SCAN_BATCH));
                cursor = scan.getCursor();
                List<String> keys = scan.getResult();
                if (!keys.isEmpty()) {
                    deleted += client.del(keys.toArray(new String[0]));
                }
            } while (!"0".equals(cursor));
            log.info("AnswerCache.invalidateTenant tenant={} → deleted {} keys", tenantId, deleted);
            return deleted;
        } catch (Exception e) {
            throw new CacheUnavailableException("invalidateTenant failed for " + tenantId, e);
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
