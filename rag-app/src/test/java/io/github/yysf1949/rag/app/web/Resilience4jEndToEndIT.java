package io.github.yysf1949.rag.app.web;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import io.github.yysf1949.rag.core.model.KbVersion;
import io.github.yysf1949.rag.core.model.PermissionMode;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.QAService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for the Resilience4j wiring (Phase 7
 * cluster 6 / design spec §13). Boots the full Spring Boot context
 * with {@code spring.rag.redis.host} pointing at a black-hole address
 * (port 1 on localhost — guaranteed to refuse TCP), then drives the
 * QA chain through several failing calls and asserts:
 *
 * <ol>
 *   <li>The breaker transitions CLOSED → OPEN after the failure
 *       threshold (configured in {@code application.yml}).</li>
 *   <li>Once OPEN, a subsequent call fails FAST with a typed
 *       {@link VectorStoreUnavailableException} that names the
 *       circuit breaker — the upstream is no longer hit.</li>
 *   <li>The {@link io.github.resilience4j.ratelimiter.RequestNotPermitted}
 *       path is exercised by the rate-limiter unit test
 *       ({@code QAServiceImplRateLimiterTest}) rather than this IT,
 *       because enforcing 100 req/s would either be flaky in CI or
 *       require multi-second sleeps.</li>
 * </ol>
 *
 * <p>Gated by {@code -DrunIT=true} like {@link RagEndToEndIT} — no
 * live Redis required (we deliberately point at a dead address to
 * simulate an outage).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rag.redis.enabled=true",
                "rag.siliconflow.enabled=false"
        })
@EnabledIfSystemProperty(named = "runIT", matches = "true")
class Resilience4jEndToEndIT {

    /**
     * We don't need a real Redis here — we want the Jedis client to
     * FAIL when search() runs. We mock {@link io.github.yysf1949.rag.redis.config.RedisConnection}
     * so the Spring context can boot (its @PostConstruct ping is bypassed),
     * and have the mock return a JedisPooled stub that throws on every
     * ftSearch call. The Resilience4j circuit breaker around that call
     * counts the failures and eventually opens.
     */
    @MockBean private io.github.yysf1949.rag.redis.config.RedisConnection redisConnection;

    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired private QAService qaService;

    @BeforeEach
    void stubDeadJedis() {
        // RedisConnection.client() returns JedisPooled (subclass of UnifiedJedis).
        // We mock the JedisPooled directly — Mockito's deep stubs won't
        // help here because we need the runtime type to match.
        redis.clients.jedis.JedisPooled dead = org.mockito.Mockito.mock(
                redis.clients.jedis.JedisPooled.class,
                org.mockito.Answers.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(dead.ftSearch(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.<redis.clients.jedis.search.FTSearchParams>any()))
                .thenThrow(new redis.clients.jedis.exceptions.JedisException("simulated outage"));
        org.mockito.Mockito.when(redisConnection.client()).thenReturn(dead);
    }

    @Test
    void breakerTripsAndShortCircuitsQaCallsWhenRedisIsUnreachable() {
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("redis");
        assertNotNull(breaker, "redis breaker must be registered via application.yml");
        // Fresh context per test class — reset to a known baseline.
        breaker.reset();

        Query q = new Query("t1", "u1", "s1", "does the breaker trip?",
                Set.of(), 5, new KbVersion("t1", "k1", 1L));

        // Drive enough failures to trip the breaker. application.yml:
        //   minimumNumberOfCalls=5, failureRateThreshold=50, window=10.
        // We do 10 calls — once the failure rate crosses 50% the breaker
        // opens. Some calls may also throw during the rewrite / embed /
        // LLM-stub path before reaching search, which still counts as a
        // recorded failure for the breaker if it hits a recorded exception.
        VectorStoreUnavailableException lastOpenException = null;
        for (int i = 0; i < 10; i++) {
            try {
                qaService.answer(q);
            } catch (VectorStoreUnavailableException ex) {
                // Either the breaker tripped ("circuit breaker OPEN") or the
                // upstream Jedis call failed ("search failed for ...").
                // Both are fine — we just want the breaker to OPEN.
                if (ex.getMessage() != null && ex.getMessage().contains("circuit breaker OPEN")) {
                    lastOpenException = ex;
                }
            } catch (RuntimeException ignore) {
                // Other failures (e.g. SiliconFlow stub not configured) are
                // expected — they don't record against the breaker but
                // shouldn't fail the test.
            }
        }

        // After the loop the breaker must be OPEN (or transitioning).
        CircuitBreaker.State finalState = breaker.getState();
        assertTrue(finalState == CircuitBreaker.State.OPEN || finalState == CircuitBreaker.State.FORCED_OPEN,
                "expected breaker to be OPEN after sustained Redis failure, was " + finalState);

        // One more call — must short-circuit with the breaker-OPEN message.
        VectorStoreUnavailableException ex = assertThrows(VectorStoreUnavailableException.class,
                () -> qaService.answer(q));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains("circuit breaker OPEN"),
                "subsequent call must short-circuit with 'circuit breaker OPEN', was: " + ex.getMessage());

        // Sanity: the request that short-circuited didn't try Redis (we
        // can only assert this indirectly — but the message naming the
        // breaker proves the breaker caught the call before search ran).
        assertNotNull(lastOpenException,
                "at least one call in the loop should have produced a breaker-OPEN exception");
    }
}
