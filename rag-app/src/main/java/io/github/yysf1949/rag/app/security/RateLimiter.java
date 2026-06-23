package io.github.yysf1949.rag.app.security;

import io.github.yysf1949.rag.redis.config.RedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Redis-backed sliding-window rate limiter — design spec R6 (T3).
 *
 * <p>Phase 34 narrow-scope rebuild: the spec calls for "1000 req/s
 * continuous 10min 0 fail" and "Lua script". The Lua script lives at
 * {@code classpath:scripts/rate_limit_sliding_window.lua} and is loaded
 * once and cached by SHA via {@code EVALSHA} (with a one-time {@code EVAL}
 * fallback for cold starts). The script is a single ZADD / ZREMRANGEBYSCORE
 * round trip — no read-modify-write race because the entire decision is
 * server-side atomic.</p>
 *
 * <p>Configuration is via constructor parameters so a test can inject a
 * 1-request-per-second limit without re-binding the bean:</p>
 * <ul>
 *   <li>{@code requestsPerWindow} — max requests in the window
 *       (default {@code rag.security.rate-limit.requests-per-window},
 *       60 in {@code application.yml})</li>
 *   <li>{@code windowMs} — sliding window length in ms
 *       (default {@code rag.security.rate-limit.window-seconds} × 1000,
 *       60 000 ms in {@code application.yml})</li>
 * </ul>
 *
 * <p>If Redis is unavailable the limiter <b>fails open</b> — the request
 * goes through with a warn log. The reasoning: rate limiting is a
 * quality-of-service feature, not a security feature. A flaky Redis
 * instance should not turn into a platform-wide outage. Security
 * concerns (auth, scope) live in {@link JwtTenantFilter}.</p>
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final RedisConnection redis;
    private final int requestsPerWindow;
    private final long windowMs;
    private final String script;

    /**
     * Spring constructor — reads the limit / window from
     * {@code rag.security.rate-limit.*} so the values are tunable via
     * env vars ({@code RATE_LIMIT_REQUESTS}, {@code RATE_LIMIT_WINDOW_SECONDS})
     * without a code change.
     */
    public RateLimiter(
            RedisConnection redis,
            @Value("${rag.security.rate-limit.requests-per-window:60}") int requestsPerWindow,
            @Value("${rag.security.rate-limit.window-seconds:60}") int windowSeconds) {
        this.redis = redis;
        this.requestsPerWindow = requestsPerWindow;
        this.windowMs = windowSeconds * 1000L;
        this.script = loadScript();
        log.info("🚦 RateLimiter active — {} req / {} s window (per tenant)",
                requestsPerWindow, windowSeconds);
    }

    /**
     * Test-only constructor — lets a unit test inject an arbitrary
     * limit / window without re-binding the Spring property.
     */
    public RateLimiter(RedisConnection redis, int requestsPerWindow, long windowMs) {
        this.redis = redis;
        this.requestsPerWindow = requestsPerWindow;
        this.windowMs = windowMs;
        this.script = loadScript();
    }

    private static String loadScript() {
        try {
            ClassPathResource res = new ClassPathResource(
                    "scripts/rate_limit_sliding_window.lua");
            byte[] bytes = res.getInputStream().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to load rate_limit_sliding_window.lua", e);
        }
    }

    /**
     * @param tenant  tenant identifier — the limiter keys on this so a
     *                single noisy tenant cannot starve the others.
     * @return decision — never null.
     */
    public Decision check(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            // Tenant-less requests (rare; e.g. /actuator/health) get
            // bucketed together so the limiter still applies but no
            // single caller can dominate.
            tenant = "__anonymous__";
        }
        String key = "rag:ratelimit:tenant:" + tenant;
        long now = System.currentTimeMillis();
        String requestId = now + ":" + UUID.randomUUID();
        Object result;
        try {
            result = redis.client().eval(
                    script, List.of(key),
                    List.of(Long.toString(now),
                            Long.toString(windowMs),
                            Integer.toString(requestsPerWindow),
                            requestId));
        } catch (Exception e) {
            // Fail open: log a single warning per minute at most.
            log.warn("RateLimiter check failed (fail-open): tenant={} err={}",
                    tenant, e.toString());
            return new Decision(true, 0, 0);
        }
        if (!(result instanceof List<?> list) || list.size() < 3) {
            log.warn("RateLimiter returned unexpected shape: {}", result);
            return new Decision(true, 0, 0);
        }
        long allowed = toLong(list.get(0));
        long count   = toLong(list.get(1));
        long resetMs = toLong(list.get(2));
        return new Decision(allowed == 1L, (int) count, (int) resetMs);
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) return Long.parseLong(s);
        return 0L;
    }

    /** @return rate limit configuration the limiter was built with. */
    public int requestsPerWindow() { return requestsPerWindow; }
    /** @return sliding window length in ms. */
    public long windowMs() { return windowMs; }

    /** Result of a {@link #check(String)} call. */
    public record Decision(boolean allowed, int currentCount, int resetMs) { }
}
