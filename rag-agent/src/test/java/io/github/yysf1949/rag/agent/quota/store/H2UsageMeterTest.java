package io.github.yysf1949.rag.agent.quota.store;

import io.github.yysf1949.rag.agent.quota.UsageMeter;
import io.github.yysf1949.rag.agent.store.StoreAutoConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2UsageMeter 集成测试 — 真实 H2 内存库 (非 mock).
 *
 * <p>走 {@link StoreAutoConfiguration#ensureAllSchema(JdbcTemplate)} 建表,
 * 跟线上启动路径一致. 覆盖 increment / get / reset / 跨月跨租户隔离.</p>
 */
class H2UsageMeterTest {

    private static JdbcTemplate jdbc;
    private static H2UsageMeter meter;

    @BeforeAll
    static void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:test_usage_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        StoreAutoConfiguration.ensureAllSchema(jdbc);
        meter = new H2UsageMeter(jdbc);
    }

    @Test
    void incrementAndGetRoundTrips() {
        long v1 = meter.increment("tenant-a", "2026-06", UsageMeter.RESOURCE_CALLS, 10L);
        assertThat(v1).isEqualTo(10L);

        long v2 = meter.increment("tenant-a", "2026-06", UsageMeter.RESOURCE_CALLS, 5L);
        assertThat(v2).isEqualTo(15L);
    }

    @Test
    void getReturnsZeroForMissing() {
        assertThat(meter.getCurrentUsage("ghost-tenant", "2026-06", UsageMeter.RESOURCE_CALLS))
                .isZero();
    }

    @Test
    void differentResourcesAreIsolated() {
        meter.increment("tenant-b", "2026-06", UsageMeter.RESOURCE_CALLS, 7L);
        meter.increment("tenant-b", "2026-06", UsageMeter.RESOURCE_TOKENS, 500L);

        assertThat(meter.getCurrentUsage("tenant-b", "2026-06", UsageMeter.RESOURCE_CALLS))
                .isEqualTo(7L);
        assertThat(meter.getCurrentUsage("tenant-b", "2026-06", UsageMeter.RESOURCE_TOKENS))
                .isEqualTo(500L);
    }

    @Test
    void resetClearsAllResourcesForMonth() {
        meter.increment("tenant-c", "2026-06", UsageMeter.RESOURCE_CALLS, 100L);
        meter.increment("tenant-c", "2026-06", UsageMeter.RESOURCE_TOKENS, 1000L);
        meter.increment("tenant-c", "2026-07", UsageMeter.RESOURCE_CALLS, 50L);  // 其它月

        meter.reset("tenant-c", "2026-06");

        assertThat(meter.getCurrentUsage("tenant-c", "2026-06", UsageMeter.RESOURCE_CALLS)).isZero();
        assertThat(meter.getCurrentUsage("tenant-c", "2026-06", UsageMeter.RESOURCE_TOKENS)).isZero();
        // 其它月不动
        assertThat(meter.getCurrentUsage("tenant-c", "2026-07", UsageMeter.RESOURCE_CALLS))
                .isEqualTo(50L);
    }
}