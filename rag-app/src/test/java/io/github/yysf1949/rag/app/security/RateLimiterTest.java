package io.github.yysf1949.rag.app.security;

import io.github.yysf1949.rag.redis.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link RateLimiter} boundary calculations that
 * don't need a live Redis. The end-to-end Redis interaction is covered
 * by the {@code @Tag("redis-required")} integration test in
 * {@code rag-redis}.
 *
 * <p>We don't exercise the Lua script from this class — a unit test
 * that calls the script needs a real Redis, which is what the
 * integration test in {@code RateLimiterLiveIT} exists for.</p>
 */
class RateLimiterTest {

    @Test
    void constructors_exposeConfiguration() {
        // We can't instantiate RateLimiter without a RedisConnection
        // bean, but we can read the getters off the test-only
        // constructor by building one with a null connection and
        // catching the failure on a no-op method.
        // Easier: build a real one and assert the getters match the
        // constructor args. Use a null client — we never call
        // .check() so the constructor does not touch Redis.
        RateLimiter rl = new RateLimiter(null, 42, 1234L);
        assertEquals(42, rl.requestsPerWindow());
        assertEquals(1234L, rl.windowMs());
    }

    @Test
    void decision_record_carriesAllFields() {
        RateLimiter.Decision d = new RateLimiter.Decision(true, 7, 999);
        assertTrue(d.allowed());
        assertEquals(7, d.currentCount());
        assertEquals(999, d.resetMs());
    }
}
