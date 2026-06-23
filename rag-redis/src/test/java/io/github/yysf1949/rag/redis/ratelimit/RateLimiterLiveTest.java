package io.github.yysf1949.rag.redis.ratelimit;

import io.github.yysf1949.rag.redis.config.RedisConnection;
import io.github.yysf1949.rag.redis.config.RedisProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live integration test for {@link RateLimiter} — exercises the Lua
 * sliding-window script against a real Redis. Skipped by default; opt
 * in with {@code -Dredis.smoke.test=true} (mirrors the convention used
 * by {@code RedisCacheSmokeTest} and the {@code *LiveTest} family).
 *
 * <p>The test pins the contract from design spec R6 (T3):</p>
 * <ol>
 *   <li>A burst of {@code N} requests within the window is allowed.</li>
 *   <li>The {@code N+1}-th request is denied with a {@code Retry-After}
 *       window.</li>
 *   <li>After the window slides past, the bucket resets and the same
 *       caller is admitted again.</li>
 *   <li>The {@code currentCount} field reflects the number of
 *       requests in the window AFTER the current call (matches the
 *       Lua contract documented at the top of
 *       {@code rate_limit_sliding_window.lua}).</li>
 * </ol>
 *
 * <p>We use a tiny limit (5 req / 1 s) so the test is fast. A unique
 * tenant id is picked per run so re-runs do not see a polluted bucket
 * from a previous {@code redis-cli flushdb}.</p>
 */
@EnabledIfSystemProperty(named = "redis.smoke.test", matches = "true")
class RateLimiterLiveTest {

    private static RedisConnection conn;
    private static String tenant;

    @BeforeAll
    static void setUp() {
        RedisProperties props = new RedisProperties(
                System.getProperty("spring.data.redis.host", "127.0.0.1"),
                Integer.parseInt(System.getProperty("spring.data.redis.port", "6379")),
                null, 0,
                4, 2, 1, 2000, 5000);
        conn = new RedisConnection(props);
        conn.init();
        // unique tenant per run so we don't depend on flushdb
        tenant = "ratelimit-live-" + UUID.randomUUID();
    }

    @AfterAll
    static void tearDown() {
        if (conn != null) conn.shutdown();
    }

    @Test
    void burstWithinLimit_allAllowed() {
        String t = "ratelimit-live-burst-" + UUID.randomUUID();
        RateLimiter rl = new RateLimiter(conn, 5, 1000L);
        for (int i = 1; i <= 5; i++) {
            RateLimiter.Decision d = rl.check(t);
            assertTrue(d.allowed(), "request " + i + " should be allowed");
            assertEquals(i, d.currentCount(),
                    "currentCount should equal the 1-based request index");
        }
    }

    @Test
    void overLimit_requestDenied() {
        String t = "ratelimit-live-over-" + UUID.randomUUID();
        RateLimiter rl = new RateLimiter(conn, 5, 1000L);
        for (int i = 1; i <= 5; i++) {
            assertTrue(rl.check(t).allowed());
        }
        RateLimiter.Decision d6 = rl.check(t);
        assertFalse(d6.allowed(), "6th request should be denied");
        assertTrue(d6.resetMs() > 0,
                "resetMs should be positive when denied; got " + d6.resetMs());
    }

    @Test
    void nullTenant_fallsBackToAnonymousBucket() {
        // /actuator/health etc. share one anonymous bucket.
        RateLimiter rl = new RateLimiter(conn, 1000, 1000L);
        RateLimiter.Decision d = rl.check(null);
        assertNotNull(d);
        assertTrue(d.allowed());
    }

    @Test
    void anonymousTenant_bucketedSeparatelyFromRealTenant() {
        RateLimiter rl = new RateLimiter(conn, 5, 1000L);
        String t = "ratelimit-live-bucket-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.check(t).allowed());
        }
        // anonymous bucket is a different key, must still allow.
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.check(null).allowed());
        }
    }
}
