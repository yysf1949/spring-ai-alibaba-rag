package io.github.yysf1949.rag.agent.quota;

/**
 * Tenant 配额仓库端口 — Phase 40 T3 (R11: 商业化第一阶段).
 *
 * <h2>六边形端口</h2>
 * <p>上游 {@code TenantQuotaEnforcer} 调 {@link #findById} 读配额;
 * {@code TenantQuotaController} 调 {@link #save} / {@link #findById} / {@link #findAll}.
 * 实现可换: {@code InMemoryTenantQuotaRepository} (default) 或 {@code H2TenantQuotaRepository} (h2 profile).</p>
 *
 * <h2>与 UsageMeter 的边界</h2>
 * <ul>
 *   <li>{@link TenantQuotaPort} — 存"配额元数据" (tier / 上限 / 降级状态)</li>
 *   <li>{@link UsageMeter} — 存"实时用量" (calls/tokens 计数)</li>
 * </ul>
 * <p>enforcer 组合两者: 先读 quota → 再读 meter → 比较 → 调用 save 写回降级状态.</p>
 */
public interface TenantQuotaPort {

    /**
     * 查询某租户的当前配额.
     *
     * @return 配额; 若不存在返回 {@link java.util.Optional#empty()}
     */
    java.util.Optional<TenantQuota> findById(String tenantId);

    /**
     * 保存/覆盖某租户的配额 (admin PUT 路径).
     *
     * @return 保存后的实体
     */
    TenantQuota save(TenantQuota quota);

    /**
     * 列出全部租户配额 (给 admin 仪表盘用).
     */
    java.util.List<TenantQuota> findAll();

    /**
     * 清除某租户的降级态 — tier 保留, {@code downgradedAt} 置空.
     * 给下月 1 号 cron 或 admin 手动恢复用.
     */
    TenantQuota clearDowngrade(String tenantId);
}