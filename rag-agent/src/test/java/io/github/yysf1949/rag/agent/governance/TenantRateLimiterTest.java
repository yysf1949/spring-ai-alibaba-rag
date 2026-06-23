package io.github.yysf1949.rag.agent.governance;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户级限流测试 — 4 个用例对齐"防单租户霸占"语义。
 */
class TenantRateLimiterTest {

    @Test
    void allowsBurstWithinQps() {
        // 50 QPS 默认 — 一次性执行 50 次应全部通过
        TenantRateLimiter limiter = new TenantRateLimiter();
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < 50; i++) {
            limiter.execute("tenant-A", () -> { ok.incrementAndGet(); return null; });
        }
        assertThat(ok.get()).isEqualTo(50);
    }

    @Test
    void rejectsAfterQpsExhausted() {
        // 调低到 5 QPS 便于测试
        TenantRateLimiter limiter = new TenantRateLimiter();
        limiter.setQps("tenant-A", 5);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            limiter.execute("tenant-A", () -> { ok.incrementAndGet(); return null; });
        }
        // 第 6 次应被拒绝
        assertThatThrownBy(() -> limiter.execute("tenant-A", () -> { ok.incrementAndGet(); return null; }))
                .isInstanceOf(TenantRateLimitedException.class)
                .hasMessageContaining("tenant-A");
        assertThat(ok.get()).isEqualTo(5);
    }

    @Test
    void multipleTenantsIsolated() {
        // tenant-A 限流不应影响 tenant-B
        TenantRateLimiter limiter = new TenantRateLimiter();
        limiter.setQps("tenant-A", 2);
        limiter.setQps("tenant-B", 2);

        // A 打满
        limiter.execute("tenant-A", () -> null);
        limiter.execute("tenant-A", () -> null);
        assertThatThrownBy(() -> limiter.execute("tenant-A", () -> null))
                .isInstanceOf(TenantRateLimitedException.class);

        // B 不受影响
        AtomicInteger bOk = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            limiter.execute("tenant-B", () -> { bOk.incrementAndGet(); return null; });
        }
        assertThat(bOk.get()).isEqualTo(2);
    }

    @Test
    void defaultQpsAdjustable() {
        TenantRateLimiter limiter = new TenantRateLimiter();
        limiter.setDefaultQps(3);
        // 不显式配置某租户 → 用 default
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            limiter.execute("tenant-NEW", () -> { ok.incrementAndGet(); return null; });
        }
        assertThatThrownBy(() -> limiter.execute("tenant-NEW", () -> null))
                .isInstanceOf(TenantRateLimitedException.class);
        assertThat(ok.get()).isEqualTo(3);
    }
}