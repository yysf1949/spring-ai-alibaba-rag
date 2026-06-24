package io.github.yysf1949.rag.agent.quota;

/**
 * Tenant 计费层级 — Phase 40 T3 (R11: 商业化第一阶段).
 *
 * <h2>层级与默认配额</h2>
 * <ul>
 *   <li>{@link #FREE} — 试用层 (低配额, 超限自动降级)</li>
 *   <li>{@link #PRO} — 付费层 (中等配额)</li>
 *   <li>{@link #ENTERPRISE} — 企业层 (高配额或按合约)</li>
 * </ul>
 *
 * <h2>降级语义</h2>
 * <p>{@link #FREE} 是被 {@code TenantQuotaEnforcer} 强制写回的"惩罚态"。
 * 任何 tier 的租户在当月超限后, enforcer 会强制把 tier 降为 FREE,
 * 并写入 {@link TenantQuota#downgradedAt()} 时间戳。
 * 该降级在下月 1 号 (或 admin 手动 PUT 配额) 才会被清除。</p>
 */
public enum TenantTier {
    FREE,
    PRO,
    ENTERPRISE;

    /**
     * 层级默认月度调用配额 — {@code TenantQuotaRepository} 首次写入时填充.
     * 数值参考 R11 商业化草案, 后续可被 admin PUT 覆盖.
     */
    public long defaultMonthlyCallLimit() {
        return switch (this) {
            case FREE       -> 1_000L;
            case PRO        -> 50_000L;
            case ENTERPRISE -> 1_000_000L;
        };
    }

    /**
     * 层级默认月度 token 配额.
     */
    public long defaultMonthlyTokenLimit() {
        return switch (this) {
            case FREE       -> 100_000L;
            case PRO        -> 10_000_000L;
            case ENTERPRISE -> 500_000_000L;
        };
    }
}