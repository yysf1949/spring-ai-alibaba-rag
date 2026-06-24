package io.github.yysf1949.rag.agent.quota.store;

import io.github.yysf1949.rag.agent.quota.TenantQuota;
import io.github.yysf1949.rag.agent.quota.TenantTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InMemoryTenantQuotaRepository} 单元测试 — 不依赖 Spring.
 *
 * <p>覆盖: findById (含 miss) / save / findAll / clearDowngrade (已有 + 缺失).</p>
 */
class InMemoryTenantQuotaRepositoryTest {

    private InMemoryTenantQuotaRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryTenantQuotaRepository();
    }

    @Test
    void saveAndFindByIdRoundTrip() {
        long now = System.currentTimeMillis();
        TenantQuota q = TenantQuota.forNewTenant("t1", TenantTier.PRO, now);
        repo.save(q);

        var found = repo.findById("t1");
        assertThat(found).isPresent();
        assertThat(found.get().tier()).isEqualTo(TenantTier.PRO);
        assertThat(found.get().monthlyCallLimit()).isEqualTo(50_000L);
    }

    @Test
    void findByIdMissReturnsEmpty() {
        assertThat(repo.findById("missing")).isEmpty();
        assertThat(repo.findById(null)).isEmpty();
    }

    @Test
    void findAllReturnsEverythingInserted() {
        long now = System.currentTimeMillis();
        repo.save(TenantQuota.forNewTenant("t1", TenantTier.FREE, now));
        repo.save(TenantQuota.forNewTenant("t2", TenantTier.PRO, now));
        repo.save(TenantQuota.forNewTenant("t3", TenantTier.ENTERPRISE, now));

        List<TenantQuota> all = repo.findAll();
        assertThat(all).hasSize(3);
        assertThat(all).extracting(TenantQuota::tenantId)
                .containsExactlyInAnyOrder("t1", "t2", "t3");
    }

    @Test
    void clearDowngradeOnExistingQuotaResetsDowngradedAt() {
        long now = System.currentTimeMillis();
        TenantQuota original = TenantQuota.forNewTenant("t1", TenantTier.PRO, now);
        TenantQuota downgraded = original.markedDowngraded(now + 1000L);
        repo.save(downgraded);

        TenantQuota cleared = repo.clearDowngrade("t1");
        assertThat(cleared.tier()).isEqualTo(TenantTier.PRO);  // tier 保留
        assertThat(cleared.downgradedAt()).isNull();
    }

    @Test
    void clearDowngradeOnMissingCreatesFreshFreeQuota() {
        TenantQuota created = repo.clearDowngrade("brand-new");
        assertThat(created.tenantId()).isEqualTo("brand-new");
        assertThat(created.tier()).isEqualTo(TenantTier.FREE);
        assertThat(created.downgradedAt()).isNull();
        // 后续能查到
        assertThat(repo.findById("brand-new")).isPresent();
    }
}