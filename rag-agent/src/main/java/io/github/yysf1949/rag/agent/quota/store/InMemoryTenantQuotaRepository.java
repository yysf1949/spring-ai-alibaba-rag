package io.github.yysf1949.rag.agent.quota.store;

import io.github.yysf1949.rag.agent.quota.TenantQuota;
import io.github.yysf1949.rag.agent.quota.TenantQuotaPort;
import io.github.yysf1949.rag.agent.quota.TenantTier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemory Tenant 配额仓库 — {@code @Profile("default")} 默认激活.
 *
 * <p>用 {@code ConcurrentHashMap} 按 tenantId 索引. 线程安全, 重启清空.
 * 跟 {@code InMemoryFeedbackRepository} 风格一致.</p>
 */
@Component
@Profile("default")
public class InMemoryTenantQuotaRepository implements TenantQuotaPort {

    private final Map<String, TenantQuota> store = new ConcurrentHashMap<>();

    @Override
    public Optional<TenantQuota> findById(String tenantId) {
        if (tenantId == null) return Optional.empty();
        return Optional.ofNullable(store.get(tenantId));
    }

    @Override
    public TenantQuota save(TenantQuota quota) {
        store.put(quota.tenantId(), quota);
        return quota;
    }

    @Override
    public List<TenantQuota> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public TenantQuota clearDowngrade(String tenantId) {
        TenantQuota cur = store.get(tenantId);
        if (cur == null) {
            // 不存在 → 返回一个 FREE tier 默认实体 (admin 后续可 PUT 覆盖)
            TenantQuota fresh = TenantQuota.forNewTenant(
                    tenantId, TenantTier.FREE, System.currentTimeMillis());
            store.put(tenantId, fresh);
            return fresh;
        }
        TenantQuota cleared = cur.clearedDowngrade();
        store.put(tenantId, cleared);
        return cleared;
    }
}