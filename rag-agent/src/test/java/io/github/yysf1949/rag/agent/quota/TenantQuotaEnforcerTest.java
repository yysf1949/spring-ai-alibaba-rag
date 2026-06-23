package io.github.yysf1949.rag.agent.quota;

import io.github.yysf1949.rag.agent.quota.store.InMemoryTenantQuotaRepository;
import io.github.yysf1949.rag.agent.quota.store.InMemoryUsageMeter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TenantQuotaEnforcer} 单元测试 — 不依赖 Spring.
 *
 * <p>覆盖 DoD 的三条核心断言:
 * <ol>
 *   <li>正常调用通过 (配额检查不误杀)</li>
 *   <li>超限抛 {@link TenantQuotaExceededException}</li>
 *   <li>超限后 tenant 自动降级为 FREE + downgradedAt 被设置</li>
 * </ol>
 *
 * <p>外加: snapshot 正确 / Micrometer gauge 注册成功 / 已降级租户再超限行为.</p>
 */
class TenantQuotaEnforcerTest {

    private InMemoryTenantQuotaRepository quotaRepo;
    private InMemoryUsageMeter meter;
    private MeterRegistry registry;
    private TenantQuotaEnforcer enforcer;

    @BeforeEach
    void setUp() {
        quotaRepo = new InMemoryTenantQuotaRepository();
        meter = new InMemoryUsageMeter();
        registry = new SimpleMeterRegistry();
        enforcer = new TenantQuotaEnforcer(quotaRepo, meter, registry);
    }

    @Test
    void firstCallForUnknownTenantSucceedsAndAutoProvisionsQuota() {
        // DoD ①: 配额检查不误杀合法调用
        String result = enforcer.execute("tenant-a", UsageMeter.RESOURCE_CALLS, 1L, () -> "ok");

        assertThat(result).isEqualTo("ok");
        // 自动建配额 (FREE tier 默认值)
        TenantQuota quota = quotaRepo.findById("tenant-a").orElseThrow();
        assertThat(quota.tier()).isEqualTo(TenantTier.FREE);
        assertThat(quota.monthlyCallLimit()).isEqualTo(1_000L);
    }

    @Test
    void usageIncrementsAfterSuccessfulCall() {
        // 多次调用, counter 应该累加
        for (int i = 0; i < 5; i++) {
            enforcer.execute("tenant-a", UsageMeter.RESOURCE_CALLS, 1L, () -> null);
        }

        String monthKey = TenantQuota.currentMonthKey();
        assertThat(meter.getCurrentUsage("tenant-a", monthKey, UsageMeter.RESOURCE_CALLS))
                .isEqualTo(5L);
    }

    @Test
    void exceedingMonthlyCallLimitThrowsAndAutoDowngrades() {
        // DoD ② + ③: 超限 → 抛异常 + 自动降级
        long now = System.currentTimeMillis();
        quotaRepo.save(new TenantQuota("tenant-b", TenantTier.PRO,
                100L,        // 把上限压低到 100 方便测试
                10_000_000L,
                now, null, null, null));

        // 用满 100
        for (int i = 0; i < 100; i++) {
            enforcer.execute("tenant-b", UsageMeter.RESOURCE_CALLS, 1L, () -> null);
        }

        // 第 101 次 → 超限 → 抛
        assertThatThrownBy(() ->
                enforcer.execute("tenant-b", UsageMeter.RESOURCE_CALLS, 1L, () -> "should not run"))
                .isInstanceOf(TenantQuotaExceededException.class)
                .hasMessageContaining("tenant-b")
                .hasMessageContaining("calls");

        // DoD ③: 自动降级到 FREE + downgradedAt 非空 + originalTier 保留
        TenantQuota after = quotaRepo.findById("tenant-b").orElseThrow();
        assertThat(after.tier()).isEqualTo(TenantTier.FREE);
        assertThat(after.downgradedAt()).isNotNull();
        assertThat(after.downgradedAt()).isGreaterThan(0L);
        assertThat(after.originalTier()).isEqualTo(TenantTier.PRO);
    }

    @Test
    void exceedingTokenLimitAlsoTriggersDowngrade() {
        // tokens 路径也要测 — 与 calls 用同一条强制降级路径
        long now = System.currentTimeMillis();
        quotaRepo.save(new TenantQuota("tenant-c", TenantTier.PRO,
                1_000_000L,
                500L,        // token 上限压低
                now, null, null, null));

        for (int i = 0; i < 5; i++) {
            enforcer.execute("tenant-c", UsageMeter.RESOURCE_TOKENS, 100L, () -> null);
        }
        // 第 6 次, delta=100 → 累计 600 > 500 → 超限
        assertThatThrownBy(() ->
                enforcer.execute("tenant-c", UsageMeter.RESOURCE_TOKENS, 100L, () -> null))
                .isInstanceOf(TenantQuotaExceededException.class)
                .hasMessageContaining("tokens");

        TenantQuota after = quotaRepo.findById("tenant-c").orElseThrow();
        assertThat(after.tier()).isEqualTo(TenantTier.FREE);
        assertThat(after.downgradedAt()).isNotNull();
        assertThat(after.originalTier()).isEqualTo(TenantTier.PRO);
    }

    @Test
    void exceedingLimitRollsBackCounterIncrement() {
        // 异常前 counter 必须是 100 (因为第 101 次没成功, 不计入)
        long now = System.currentTimeMillis();
        quotaRepo.save(new TenantQuota("tenant-d", TenantTier.PRO,
                100L, 10_000_000L, now, null, null, null));
        for (int i = 0; i < 100; i++) {
            enforcer.execute("tenant-d", UsageMeter.RESOURCE_CALLS, 1L, () -> null);
        }
        String monthKey = TenantQuota.currentMonthKey();
        long beforeFail = meter.getCurrentUsage("tenant-d", monthKey, UsageMeter.RESOURCE_CALLS);
        assertThat(beforeFail).isEqualTo(100L);

        try {
            enforcer.execute("tenant-d", UsageMeter.RESOURCE_CALLS, 1L, () -> null);
        } catch (TenantQuotaExceededException ignored) {
            // expected
        }

        // 第 101 次的 +1 没计入 → counter 仍为 100
        assertThat(meter.getCurrentUsage("tenant-d", monthKey, UsageMeter.RESOURCE_CALLS))
                .isEqualTo(100L);
    }

    @Test
    void snapshotReflectsCurrentUsageAndLimits() {
        long now = System.currentTimeMillis();
        quotaRepo.save(new TenantQuota("tenant-e", TenantTier.PRO,
                100L, 1000L, now, null, null, null));
        for (int i = 0; i < 25; i++) {
            enforcer.execute("tenant-e", UsageMeter.RESOURCE_CALLS, 1L, () -> null);
        }
        enforcer.execute("tenant-e", UsageMeter.RESOURCE_TOKENS, 200L, () -> null);

        TenantQuotaEnforcer.QuotaSnapshot snap = enforcer.snapshot("tenant-e");
        assertThat(snap.quota().tier()).isEqualTo(TenantTier.PRO);
        assertThat(snap.currentCalls()).isEqualTo(25L);
        assertThat(snap.currentTokens()).isEqualTo(200L);
        assertThat(snap.callUsageRatio()).isEqualTo(0.25);   // 25/100
        assertThat(snap.tokenUsageRatio()).isEqualTo(0.2);   // 200/1000
    }

    @Test
    void micrometerGaugeIsRegisteredWithTenantAndResourceTags() {
        long now = System.currentTimeMillis();
        quotaRepo.save(new TenantQuota("tenant-f", TenantTier.PRO,
                1000L, 10000L, now, null, null, null));
        enforcer.execute("tenant-f", UsageMeter.RESOURCE_CALLS, 50L, () -> null);

        var gauge = registry.find("tenant.quota.usage_ratio")
                .tags("tenant", "tenant-f", "resource", UsageMeter.RESOURCE_CALLS)
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.05);   // 50/1000
    }

    @Test
    void alreadyDowngradedTenantStillThrowsButPreservesOriginalTier() {
        // 已经处于降级态, 再超限时不应该再次强制降级 (tier 已经是 FREE, originalTier 保留)
        long now = System.currentTimeMillis();
        TenantQuota preDowngraded = new TenantQuota("tenant-g", TenantTier.FREE,
                10L, 1000L, now, null, now, TenantTier.PRO);
        quotaRepo.save(preDowngraded);

        // 累计 10 次后第 11 次必超限
        for (int i = 0; i < 10; i++) {
            enforcer.execute("tenant-g", UsageMeter.RESOURCE_CALLS, 1L, () -> null);
        }

        assertThatThrownBy(() ->
                enforcer.execute("tenant-g", UsageMeter.RESOURCE_CALLS, 1L, () -> null))
                .isInstanceOf(TenantQuotaExceededException.class);

        TenantQuota after = quotaRepo.findById("tenant-g").orElseThrow();
        assertThat(after.tier()).isEqualTo(TenantTier.FREE);
        // originalTier 应保留 (首次降级时记录的 PRO)
        assertThat(after.originalTier()).isEqualTo(TenantTier.PRO);
        assertThat(after.downgradedAt()).isNotNull();
    }
}