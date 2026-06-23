package io.github.yysf1949.rag.agent.quota;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TenantQuota} record 验证 + 不变量测试.
 *
 * <p>覆盖: 字段校验 / downgradedAt + tier 联动 / clearedDowngrade / markedDowngraded / currentMonthKey.</p>
 */
class TenantQuotaTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long LATER = NOW + 86_400_000L;  // +1 day

    @Test
    void forNewTenantUsesTierDefaults() {
        TenantQuota q = TenantQuota.forNewTenant("tenant-a", TenantTier.PRO, NOW);
        assertThat(q.tenantId()).isEqualTo("tenant-a");
        assertThat(q.tier()).isEqualTo(TenantTier.PRO);
        assertThat(q.monthlyCallLimit()).isEqualTo(TenantTier.PRO.defaultMonthlyCallLimit());
        assertThat(q.monthlyTokenLimit()).isEqualTo(TenantTier.PRO.defaultMonthlyTokenLimit());
        assertThat(q.effectiveFrom()).isEqualTo(NOW);
        assertThat(q.effectiveTo()).isNull();
        assertThat(q.downgradedAt()).isNull();
    }

    @Test
    void rejectsBlankTenantId() {
        assertThatThrownBy(() -> TenantQuota.forNewTenant("", TenantTier.FREE, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void rejectsNullTier() {
        assertThatThrownBy(() -> new TenantQuota(
                "t1", null, 100L, 1000L, NOW, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tier");
    }

    @Test
    void rejectsNegativeLimits() {
        assertThatThrownBy(() -> new TenantQuota(
                "t1", TenantTier.FREE, -1L, 1000L, NOW, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("monthlyCallLimit");

        assertThatThrownBy(() -> new TenantQuota(
                "t1", TenantTier.FREE, 100L, -1L, NOW, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("monthlyTokenLimit");
    }

    @Test
    void rejectsInvertedEffectiveWindow() {
        assertThatThrownBy(() -> new TenantQuota(
                "t1", TenantTier.PRO, 100L, 1000L, LATER, NOW, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveTo must be > effectiveFrom");
    }

    @Test
    void downgradedRequiresFreeTierAndOriginalTier() {
        // 不变量: downgradedAt != null ⇒ tier 必为 FREE, originalTier 必非空且 != FREE
        assertThatThrownBy(() -> new TenantQuota(
                "t1", TenantTier.PRO, 100L, 1000L, NOW, null, NOW, TenantTier.PRO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be FREE");

        // tier=FREE 但 originalTier 缺失 → 失败
        assertThatThrownBy(() -> new TenantQuota(
                "t1", TenantTier.FREE, 100L, 1000L, NOW, null, NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalTier");

        // tier=FREE 但 originalTier 也是 FREE → 失败 (没有意义)
        assertThatThrownBy(() -> new TenantQuota(
                "t1", TenantTier.FREE, 100L, 1000L, NOW, null, NOW, TenantTier.FREE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalTier");
    }

    @Test
    void normalStateRejectsOriginalTier() {
        // 不变量: downgradedAt == null ⇒ originalTier 必为空
        assertThatThrownBy(() -> new TenantQuota(
                "t1", TenantTier.PRO, 100L, 1000L, NOW, null, null, TenantTier.PRO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalTier");
    }

    @Test
    void markedDowngradeForcesFreeAndStampsTime() {
        TenantQuota original = TenantQuota.forNewTenant("t1", TenantTier.PRO, NOW);
        TenantQuota downgraded = original.markedDowngraded(LATER);

        assertThat(downgraded.tier()).isEqualTo(TenantTier.FREE);
        assertThat(downgraded.downgradedAt()).isEqualTo(LATER);
        assertThat(downgraded.monthlyCallLimit()).isEqualTo(original.monthlyCallLimit());
    }

    @Test
    void clearedDowngradeKeepsTierButResetsDowngradedAt() {
        TenantQuota original = TenantQuota.forNewTenant("t1", TenantTier.PRO, NOW);
        TenantQuota downgraded = original.markedDowngraded(LATER);
        TenantQuota cleared = downgraded.clearedDowngrade();

        // 关键: cleared 之后 tier 恢复到 PRO (原 tier), downgradedAt 变 null, originalTier 变 null
        assertThat(cleared.tier()).isEqualTo(TenantTier.PRO);
        assertThat(cleared.downgradedAt()).isNull();
        assertThat(cleared.originalTier()).isNull();
        assertThat(cleared.effectiveFrom()).isEqualTo(NOW);
    }

    @Test
    void markedDowngradeSetsOriginalTierForFirstTime() {
        TenantQuota original = TenantQuota.forNewTenant("t1", TenantTier.ENTERPRISE, NOW);
        TenantQuota downgraded = original.markedDowngraded(LATER);

        assertThat(downgraded.tier()).isEqualTo(TenantTier.FREE);
        assertThat(downgraded.downgradedAt()).isEqualTo(LATER);
        assertThat(downgraded.originalTier()).isEqualTo(TenantTier.ENTERPRISE);
    }

    @Test
    void markedDowngradeSecondTimePreservesOriginalTier() {
        TenantQuota original = TenantQuota.forNewTenant("t1", TenantTier.PRO, NOW);
        TenantQuota firstDown = original.markedDowngraded(LATER);
        TenantQuota secondDown = firstDown.markedDowngraded(LATER + 1000L);

        // 第二次降级: 只刷新 downgradedAt, originalTier 不变
        assertThat(secondDown.tier()).isEqualTo(TenantTier.FREE);
        assertThat(secondDown.downgradedAt()).isEqualTo(LATER + 1000L);
        assertThat(secondDown.originalTier()).isEqualTo(TenantTier.PRO);
    }

    @Test
    void currentMonthKeyIsYYYYDashMM() {
        String key = TenantQuota.currentMonthKey();
        assertThat(key).matches("\\d{4}-\\d{2}");
    }

    @Test
    void tierDefaults() {
        // 锁定默认值, 防止后续被无意识改写
        assertThat(TenantTier.FREE.defaultMonthlyCallLimit()).isEqualTo(1_000L);
        assertThat(TenantTier.PRO.defaultMonthlyCallLimit()).isEqualTo(50_000L);
        assertThat(TenantTier.ENTERPRISE.defaultMonthlyCallLimit()).isEqualTo(1_000_000L);

        assertThat(TenantTier.FREE.defaultMonthlyTokenLimit()).isEqualTo(100_000L);
        assertThat(TenantTier.PRO.defaultMonthlyTokenLimit()).isEqualTo(10_000_000L);
        assertThat(TenantTier.ENTERPRISE.defaultMonthlyTokenLimit()).isEqualTo(500_000_000L);
    }
}