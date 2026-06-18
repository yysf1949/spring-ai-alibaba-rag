package io.github.yysf1949.rag.agent.governance;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRateLimiterTest {

    @Test
    void allowsWithinLimit() {
        RateLimiterRegistry registry = RateLimiterRegistry.of(
                RateLimiterConfig.custom()
                        .limitForPeriod(5)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build());
        AgentRateLimiter limiter = new AgentRateLimiter(registry);
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            String result = limiter.execute("kb_search", () -> "ok-" + idx);
            assertThat(result).startsWith("ok-");
        }
    }

    @Test
    void blocksBeyondLimit() {
        RateLimiterRegistry registry = RateLimiterRegistry.of(
                RateLimiterConfig.custom()
                        .limitForPeriod(2)
                        .limitRefreshPeriod(Duration.ofSeconds(10))
                        .timeoutDuration(Duration.ZERO)
                        .build());
        AgentRateLimiter limiter = new AgentRateLimiter(registry);
        // 前 2 次成功
        limiter.execute("kb_search", () -> "a");
        limiter.execute("kb_search", () -> "b");
        // 第 3 次被拒
        assertThatThrownBy(() -> limiter.execute("kb_search", () -> "c"))
                .isInstanceOf(io.github.resilience4j.ratelimiter.RequestNotPermitted.class);
    }

    @Test
    void perToolLimiter() {
        RateLimiterRegistry registry = RateLimiterRegistry.of(
                RateLimiterConfig.custom()
                        .limitForPeriod(1)
                        .limitRefreshPeriod(Duration.ofSeconds(10))
                        .timeoutDuration(Duration.ZERO)
                        .build());
        AgentRateLimiter limiter = new AgentRateLimiter(registry);
        // 工具 A 触发限流, 工具 B 不受影响
        limiter.execute("tool-a", () -> "a");
        assertThatThrownBy(() -> limiter.execute("tool-a", () -> "a2"))
                .isInstanceOf(io.github.resilience4j.ratelimiter.RequestNotPermitted.class);
        // 工具 B 仍可用
        String result = limiter.execute("tool-b", () -> "b");
        assertThat(result).isEqualTo("b");
    }
}
