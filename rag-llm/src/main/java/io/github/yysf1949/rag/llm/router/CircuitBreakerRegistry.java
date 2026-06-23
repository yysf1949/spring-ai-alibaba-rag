package io.github.yysf1949.rag.llm.router;

import io.github.yysf1949.rag.agent.governance.FailureClassification;
import io.github.yysf1949.rag.agent.governance.FailureClassificationRouter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 38: 熔断注册表 — 管理每个 Provider 的熔断状态。
 *
 * <h2>熔断策略</h2>
 * <ul>
 *   <li>失败窗口: 最近 N 次调用 (默认 10 次)</li>
 *   <li>失败阈值: 失败率超过 50% 则熔断</li>
 *   <li>恢复超时: 熔断 30 秒后尝试半开 (允许 1 次测试调用)</li>
 *   <li>半开成功 → 关闭熔断; 半开失败 → 重新熔断</li>
 * </ul>
 *
 * <h2>与 FallbackRouter 联动</h2>
 * <p>当熔断触发时，通过 {@link FailureClassificationRouter} 发送 {@code LIMITS} 事件，
 * 触发 {@code FallbackStrategy.switchProvider()}。</p>
 */
@Component
public class CircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRegistry.class);

    private final ConcurrentMap<String, ProviderCircuit> circuits = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final FailureClassificationRouter failureRouter;

    /** 熔断配置 */
    private final int failureThreshold;
    private final int failureWindow;
    private final long recoveryTimeoutMs;

    public CircuitBreakerRegistry(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            FailureClassificationRouter failureRouter) {
        this(meterRegistryProvider, failureRouter, 5, 10, 30_000);
    }

    public CircuitBreakerRegistry(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            FailureClassificationRouter failureRouter,
            int failureThreshold, int failureWindow, long recoveryTimeoutMs) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.failureRouter = failureRouter;
        this.failureThreshold = failureThreshold;
        this.failureWindow = failureWindow;
        this.recoveryTimeoutMs = recoveryTimeoutMs;
    }

    /** 获取或创建 provider 的熔断器 */
    private ProviderCircuit getCircuit(String providerId) {
        return circuits.computeIfAbsent(providerId, id -> {
            ProviderCircuit circuit = new ProviderCircuit(id);
            if (meterRegistry != null) {
                Counter.builder("llm.circuit_breaker.state")
                        .tag("provider", id)
                        .register(meterRegistry);
            }
            return circuit;
        });
    }

    /** 检查 provider 的熔断是否已打开 */
    public boolean isCircuitOpen(String providerId) {
        return getCircuit(providerId).isCircuitOpen();
    }

    /** 获取熔断状态 */
    public CircuitState getState(String providerId) {
        return getCircuit(providerId).getState();
    }

    /** 记录成功调用 */
    public void recordSuccess(String providerId) {
        ProviderCircuit circuit = getCircuit(providerId);
        circuit.recordSuccess();
    }

    /** 记录失败调用 */
    public void recordFailure(String providerId, String reason) {
        ProviderCircuit circuit = getCircuit(providerId);
        boolean wasClosed = !circuit.isCircuitOpen();
        circuit.recordFailure();

        if (circuit.isCircuitOpen() && wasClosed) {
            log.warn("Circuit OPEN for provider {}: failure threshold reached. reason={}",
                    providerId, reason);
            // 联动 FallbackRouter: 发送 LIMITS 事件触发切换
            if (failureRouter != null) {
                try {
                    failureRouter.route(FailureClassification.Category.LIMITS,
                            "Circuit breaker opened for provider: " + providerId);
                } catch (Exception e) {
                    log.warn("Failed to route fallback for {}", providerId, e);
                }
            }
        }
    }

    /** 手动重置熔断器 */
    public void reset(String providerId) {
        ProviderCircuit circuit = getCircuit(providerId);
        circuit.reset();
        log.info("Circuit RESET for provider {}", providerId);
    }

    /** 获取所有熔断状态 */
    public java.util.Map<String, CircuitState> getAllStates() {
        java.util.Map<String, CircuitState> states = new java.util.HashMap<>();
        for (String id : circuits.keySet()) {
            states.put(id, getState(id));
        }
        return states;
    }

    /** Provider 熔断状态 */
    public record CircuitState(
            String providerId,
            String state, // CLOSED, OPEN, HALF_OPEN
            int failureCount,
            int successCount,
            long lastFailureTime,
            long lastStateChangeTime
    ) {}

    /** 单个 Provider 的熔断器 */
    private class ProviderCircuit {
        private final String providerId;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final int windowSize;
        private final AtomicInteger windowIndex = new AtomicInteger(0);
        private final AtomicLong[] failureTimestamps;
        private volatile CircuitState state;
        private volatile long stateChangedAt = System.currentTimeMillis();
        private volatile long lastFailureAt = 0;

        ProviderCircuit(String providerId) {
            this.providerId = providerId;
            this.windowSize = failureWindow;
            this.failureTimestamps = new AtomicLong[failureWindow];
            for (int i = 0; i < windowSize; i++) {
                failureTimestamps[i] = new AtomicLong(0);
            }
            this.state = new CircuitState(
                    providerId, "CLOSED", 0, 0, 0, System.currentTimeMillis());
        }

        boolean isCircuitOpen() {
            return "OPEN".equals(state.state());
        }

        CircuitState getState() {
            // 检查是否需要进入半开状态
            if ("OPEN".equals(state.state())) {
                if (System.currentTimeMillis() - stateChangedAt >= recoveryTimeoutMs) {
                    state = new CircuitState(
                            providerId, "HALF_OPEN", failureCount.get(), successCount.get(),
                            lastFailureAt, stateChangedAt);
                    failureCount.set(0);
                    log.info("Circuit HALF_OPEN for provider {} (recovery timeout)", providerId);
                }
            }
            return state;
        }

        void recordSuccess() {
            CircuitState current = getState();
            if ("HALF_OPEN".equals(current.state())) {
                // 半开成功 → 关闭熔断
                state = new CircuitState(providerId, "CLOSED", 0, successCount.incrementAndGet(),
                        lastFailureAt, System.currentTimeMillis());
                stateChangedAt = System.currentTimeMillis();
                failureCount.set(0);
                log.info("Circuit CLOSED for provider {} (recovery successful)", providerId);
            } else {
                successCount.incrementAndGet();
            }
        }

        void recordFailure() {
            long now = System.currentTimeMillis();
            lastFailureAt = now;

            // 滑动窗口: 替换最旧的失败记录
            int idx = windowIndex.getAndIncrement() % windowSize;
            failureTimestamps[idx].set(now);

            int recentFailures = countRecentFailures(now);
            failureCount.set(recentFailures);

            CircuitState current = getState();
            if ("HALF_OPEN".equals(current.state())) {
                // 半开失败 → 重新打开
                state = new CircuitState(providerId, "OPEN", recentFailures, 0,
                        now, System.currentTimeMillis());
                stateChangedAt = System.currentTimeMillis();
                log.info("Circuit OPEN for provider {} (half-open failure)", providerId);
            } else if (recentFailures >= failureThreshold) {
                state = new CircuitState(providerId, "OPEN", recentFailures, 0,
                        now, System.currentTimeMillis());
                stateChangedAt = System.currentTimeMillis();
            }
        }

        private int countRecentFailures(long now) {
            int count = 0;
            for (AtomicLong ts : failureTimestamps) {
                long t = ts.get();
                if (t > 0 && now - t < recoveryTimeoutMs) {
                    count++;
                }
            }
            return count;
        }

        void reset() {
            failureCount.set(0);
            successCount.set(0);
            windowIndex.set(0);
            for (AtomicLong ts : failureTimestamps) ts.set(0);
            state = new CircuitState(providerId, "CLOSED", 0, 0, 0, System.currentTimeMillis());
            stateChangedAt = System.currentTimeMillis();
            lastFailureAt = 0;
        }
    }
}
