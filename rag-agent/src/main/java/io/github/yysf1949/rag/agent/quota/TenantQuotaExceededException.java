package io.github.yysf1949.rag.agent.quota;

/**
 * Tenant 配额超限异常 — Phase 40 T3 (R11: 商业化第一阶段).
 *
 * <p>触发时机: {@link TenantQuotaEnforcer#checkAndIncrement} 发现
 * 当月用量 ({@code calls} 或 {@code tokens}) 即将超过 {@link TenantQuota} 的
 * 月度上限时抛出. 调用方应回退本次调用, 返回 429 给调用者.</p>
 *
 * <p>异常被抛出时, enforcer 已经把 tier 强制降为 FREE 并写入 downgradedAt —
 * 见 enforcer 注释. 因此 catch 此异常后无需再做"惩罚"逻辑.</p>
 *
 * <h2>与 {@link TenantRateLimitedException} 的边界</h2>
 * <ul>
 *   <li>{@link TenantRateLimitedException} — 短窗口 QPS 超限 (Resilience4j, 秒级)</li>
 *   <li>{@link TenantQuotaExceededException} — 长窗口配额超限 (按月, 商业化)</li>
 * </ul>
 */
public class TenantQuotaExceededException extends RuntimeException {

    private final String tenantId;
    private final String resource;     // "calls" / "tokens"
    private final long currentUsage;
    private final long limit;
    private final String monthKey;     // 例如 "2026-06"

    public TenantQuotaExceededException(String tenantId, String resource,
                                        long currentUsage, long limit, String monthKey) {
        super(String.format(
                "Tenant quota exceeded: tenant=%s resource=%s usage=%d limit=%d month=%s "
                        + "(tenant auto-downgraded to FREE until next month)",
                tenantId, resource, currentUsage, limit, monthKey));
        this.tenantId = tenantId;
        this.resource = resource;
        this.currentUsage = currentUsage;
        this.limit = limit;
        this.monthKey = monthKey;
    }

    public String getTenantId()   { return tenantId; }
    public String getResource()   { return resource; }
    public long getCurrentUsage() { return currentUsage; }
    public long getLimit()        { return limit; }
    public String getMonthKey()   { return monthKey; }
}