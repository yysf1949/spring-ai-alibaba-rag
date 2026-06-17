package io.github.yysf1949.rag.redis.vector;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import io.github.yysf1949.rag.core.model.PermissionMode;
import io.github.yysf1949.rag.redis.config.RedisConnection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Hermetic test for the Redis circuit breaker wiring on
 * {@link RedisVectorStore#search}. Verifies the full state machine:
 *
 * <ol>
 *   <li><b>CLOSED</b> — search fails normally (vector store unavailable),
 *       breaker counts the failure.</li>
 *   <li><b>OPEN</b> — after the failure-rate threshold is crossed, the
 *       breaker opens and subsequent calls short-circuit with a
 *       {@link VectorStoreUnavailableException} carrying the
 *       "circuit breaker OPEN" message — the upstream is no longer
 *       hit at all.</li>
 *   <li><b>HALF_OPEN</b> — after the wait duration elapses, the breaker
 *       tries one probe call. If the probe fails, it re-opens.</li>
 * </ol>
 *
 * <p>No live Redis required — {@link RedisConnection} and
 * {@link RedisIndexManager} are Mockito mocks. Spec §13 / Phase 7 C6.</p>
 */
@ExtendWith(MockitoExtension.class)
class RedisVectorStoreCircuitBreakerTest {

    /** Aggressive tuning so the test trips the breaker with only a few calls. */
    private static final CircuitBreakerConfig FAST = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(4)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofMillis(200))
            .permittedNumberOfCallsInHalfOpenState(1)
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            .recordExceptions(JedisException.class, VectorStoreUnavailableException.class)
            .build();

    @Mock private RedisConnection connection;
    @Mock private RedisIndexManager indexManager;
    @Mock private JedisPooled jedis;

    private CircuitBreaker breaker;
    private RedisVectorStore store;
    private float[] queryVector = new float[]{0.1f, 0.2f, 0.3f};

    @BeforeEach
    void setUp() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(FAST);
        breaker = registry.circuitBreaker("redis");

        // Default: jedis.ftSearch always throws — drives the failure path.
        when(connection.client()).thenReturn(jedis);
        when(jedis.ftSearch(anyString(), anyString(), any())).thenThrow(new JedisException("simulated outage"));

        store = new RedisVectorStore(connection, indexManager, new SimpleMeterRegistry(), registry);
    }

    @Test
    void stateTransitionsFromClosedToOpenAfterFailureThreshold() {
        // CLOSED initially.
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());

        // Two failures — meets minimumNumberOfCalls=2 with failureRate=100% > 50%.
        for (int i = 0; i < 2; i++) {
            assertThrows(VectorStoreUnavailableException.class,
                    () -> store.search(queryVector, "t1", "kb1", 1L,
                            List.of(), PermissionMode.OR, 5),
                    "call #" + i + " should fail with vector-store-unavailable");
        }

        // Threshold tripped → breaker should be OPEN.
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState(),
                "breaker must trip to OPEN after failure threshold");

        // Next call short-circuits — exception message names the breaker state.
        VectorStoreUnavailableException ex = assertThrows(VectorStoreUnavailableException.class,
                () -> store.search(queryVector, "t1", "kb1", 1L,
                        List.of(), PermissionMode.OR, 5));
        assertEquals(true,
                ex.getMessage() != null && ex.getMessage().contains("circuit breaker OPEN"),
                "open-circuit exception should mention 'circuit breaker OPEN', was: " + ex.getMessage());
    }

    @Test
    void openCircuitEventuallyTransitionsToHalfOpenAfterWait() throws Exception {
        // Trip the breaker.
        for (int i = 0; i < 2; i++) {
            assertThrows(VectorStoreUnavailableException.class,
                    () -> store.search(queryVector, "t1", "kb1", 1L,
                            List.of(), PermissionMode.OR, 5));
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // Wait past waitDurationInOpenState (200ms) so the next call would
        // attempt the half-open transition. We don't sleep the full window —
        // instead drive the state machine directly via the registry API,
        // which is what production would do automatically when
        // automaticTransitionFromOpenToHalfOpenEnabled=true.
        breaker.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState(),
                "breaker should be HALF_OPEN after manual transition");

        // The probe still fails (jedis still throwing) → re-OPENs.
        assertThrows(VectorStoreUnavailableException.class,
                () -> store.search(queryVector, "t1", "kb1", 1L,
                        List.of(), PermissionMode.OR, 5));
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState(),
                "breaker must re-OPEN after a failed half-open probe");
    }
}
