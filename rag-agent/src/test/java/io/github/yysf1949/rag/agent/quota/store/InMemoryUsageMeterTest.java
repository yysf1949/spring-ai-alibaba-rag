package io.github.yysf1949.rag.agent.quota.store;

import io.github.yysf1949.rag.agent.quota.UsageMeter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link InMemoryUsageMeter} 单元测试 — 不依赖 Spring.
 *
 * <p>覆盖: increment / getCurrentUsage / reset / 跨 tenant 隔离 / 负 delta 拒绝.</p>
 */
class InMemoryUsageMeterTest {

    private InMemoryUsageMeter meter;

    @BeforeEach
    void setUp() {
        meter = new InMemoryUsageMeter();
    }

    @Test
    void incrementAndGetReturnsCorrectValue() {
        long v1 = meter.increment("t1", "2026-06", UsageMeter.RESOURCE_CALLS, 5L);
        assertThat(v1).isEqualTo(5L);
        long v2 = meter.increment("t1", "2026-06", UsageMeter.RESOURCE_CALLS, 3L);
        assertThat(v2).isEqualTo(8L);
    }

    @Test
    void getCurrentUsageReturnsZeroForUnseenKey() {
        assertThat(meter.getCurrentUsage("t1", "2026-06", UsageMeter.RESOURCE_CALLS))
                .isZero();
    }

    @Test
    void resourcesAreIsolated() {
        meter.increment("t1", "2026-06", UsageMeter.RESOURCE_CALLS, 7L);
        meter.increment("t1", "2026-06", UsageMeter.RESOURCE_TOKENS, 1000L);

        assertThat(meter.getCurrentUsage("t1", "2026-06", UsageMeter.RESOURCE_CALLS))
                .isEqualTo(7L);
        assertThat(meter.getCurrentUsage("t1", "2026-06", UsageMeter.RESOURCE_TOKENS))
                .isEqualTo(1000L);
    }

    @Test
    void monthsAreIsolated() {
        meter.increment("t1", "2026-06", UsageMeter.RESOURCE_CALLS, 5L);
        meter.increment("t1", "2026-07", UsageMeter.RESOURCE_CALLS, 2L);

        assertThat(meter.getCurrentUsage("t1", "2026-06", UsageMeter.RESOURCE_CALLS))
                .isEqualTo(5L);
        assertThat(meter.getCurrentUsage("t1", "2026-07", UsageMeter.RESOURCE_CALLS))
                .isEqualTo(2L);
    }

    @Test
    void tenantsAreIsolated() {
        meter.increment("tenant-a", "2026-06", UsageMeter.RESOURCE_CALLS, 100L);
        meter.increment("tenant-b", "2026-06", UsageMeter.RESOURCE_CALLS, 50L);

        assertThat(meter.getCurrentUsage("tenant-a", "2026-06", UsageMeter.RESOURCE_CALLS))
                .isEqualTo(100L);
        assertThat(meter.getCurrentUsage("tenant-b", "2026-06", UsageMeter.RESOURCE_CALLS))
                .isEqualTo(50L);
    }

    @Test
    void zeroDeltaIsNoOpButReturnsCurrentValue() {
        meter.increment("t1", "2026-06", UsageMeter.RESOURCE_CALLS, 5L);
        long v = meter.increment("t1", "2026-06", UsageMeter.RESOURCE_CALLS, 0L);
        assertThat(v).isEqualTo(5L);
    }

    @Test
    void negativeDeltaThrows() {
        assertThatThrownBy(() ->
                meter.increment("t1", "2026-06", UsageMeter.RESOURCE_CALLS, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delta");
    }

    @Test
    void resetRemovesAllResourcesForThatMonth() {
        meter.increment("t1", "2026-06", UsageMeter.RESOURCE_CALLS, 10L);
        meter.increment("t1", "2026-06", UsageMeter.RESOURCE_TOKENS, 100L);
        meter.increment("t1", "2026-07", UsageMeter.RESOURCE_CALLS, 5L);  // 其它月

        meter.reset("t1", "2026-06");

        assertThat(meter.getCurrentUsage("t1", "2026-06", UsageMeter.RESOURCE_CALLS)).isZero();
        assertThat(meter.getCurrentUsage("t1", "2026-06", UsageMeter.RESOURCE_TOKENS)).isZero();
        // 其它月的不动
        assertThat(meter.getCurrentUsage("t1", "2026-07", UsageMeter.RESOURCE_CALLS))
                .isEqualTo(5L);
    }
}