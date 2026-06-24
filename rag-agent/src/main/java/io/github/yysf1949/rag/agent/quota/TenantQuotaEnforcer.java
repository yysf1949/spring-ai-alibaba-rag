package io.github.yysf1949.rag.agent.quota;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Tenant 配额执行器 — Phase 40 T3 (R11: 商业化第一阶段).
 *
 * <h2>职责</h2>
 * <ol>
 *   <li>调用前查 {@link TenantQuotaPort} 拿当月配额 (无记录 → 用 FREE 默认值)</li>
 *   <li>调用前查 {@link UsageMeter} 拿当前用量, 比较是否超限</li>
 *   <li>调用成功后由 enforcer 调 {@link UsageMeter#increment} 计入</li>
 *   <li>若超限 → 强制 tier = FREE, 写 downgradedAt, 抛 {@link TenantQuotaExceededException}</li>
 * </ol>
 *
 * <h2>调用模式</h2>
 * <pre>{@code
 * quotaEnforcer.execute("tenant-a", UsageMeter.RESOURCE_CALLS, 1L, () -> {
 *     return agentService.invoke(...);
 * });
 * }</pre>
 *
 * <h2>自动降级 (auto-downgrade)</h2>
 * <p>超限后 enforcer 强制把 tier 改写为 FREE 并写入 downgradedAt. 后续调用
 * 仍走 enforcer, 但因为 tier 已经是 FREE, 等同于"被强制试用层".
 * 该降级在下月 1 号或 admin 主动 {@code clearDowngrade} 时清除.</p>
 *
 * <h2>Micrometer 指标</h2>
 * <p>每个 tenant 注册一个 gauge {@code tenant.quota.usage_ratio} (按 resource 标签),
 * value = currentUsage / limit, 范围 [0, 1+]. 上限 ≥ 1 即代表超限风险.</p>
 */
@Component
public class TenantQuotaEnforcer {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaEnforcer.class);

    private final TenantQuotaPort quotaPort;
    private final UsageMeter usageMeter;
    private final MeterRegistry meterRegistry;

    /** 已注册的 gauge cache — 同一 tenant+resource 只注册一次. */
    private final ConcurrentMap<String, java.util.concurrent.atomic.AtomicReference<Double>> gaugeCache
            = new ConcurrentHashMap<>();

    public TenantQuotaEnforcer(TenantQuotaPort quotaPort,
                               UsageMeter usageMeter,
                               MeterRegistry meterRegistry) {
        this.quotaPort = quotaPort;
        this.usageMeter = usageMeter;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 在配额检查保护下执行 supplier — 检查 + 计入 + 可能的降级一次完成.
     *
     * @param tenantId 租户 ID
     * @param resource 资源类型 (calls/tokens)
     * @param delta    本次调用消耗量
     * @param action   业务逻辑
     * @return supplier 返回值
     * @throws TenantQuotaExceededException 超限
     */
    public <T> T execute(String tenantId, String resource, long delta, Supplier<T> action) {
        TenantQuota quota = quotaPort.findById(tenantId)
                .orElseGet(() -> quotaPort.save(TenantQuota.forNewTenant(
                        tenantId, TenantTier.FREE, System.currentTimeMillis())));

        String monthKey = TenantQuota.currentMonthKey();
        long current = usageMeter.getCurrentUsage(tenantId, monthKey, resource);
        long limit = limitFor(quota, resource);

        if (limit > 0 && current + delta > limit) {
            // 超限 → 强制降级 → 抛异常
            TenantQuota downgraded = quota.markedDowngraded(System.currentTimeMillis());
            quotaPort.save(downgraded);
            log.warn("Tenant quota exceeded: tenant={} resource={} current={} limit={} "
                            + "month={} → auto-downgrade to FREE",
                    tenantId, resource, current, limit, monthKey);
            throw new TenantQuotaExceededException(tenantId, resource, current, limit, monthKey);
        }

        // 未超限 → 执行业务 → 计入用量
        try {
            T result = action.get();
            usageMeter.increment(tenantId, monthKey, resource, delta);
            refreshGauge(tenantId, resource, current + delta, limit);
            return result;
        } catch (RuntimeException re) {
            // 业务异常不计入 (避免错误调用占用配额)
            throw re;
        }
    }

    /**
     * 只检查不执行 — 用于 GET /quota 查看剩余配额, 不消耗.
     */
    public QuotaSnapshot snapshot(String tenantId) {
        TenantQuota quota = quotaPort.findById(tenantId)
                .orElseGet(() -> TenantQuota.forNewTenant(
                        tenantId, TenantTier.FREE, System.currentTimeMillis()));
        String monthKey = TenantQuota.currentMonthKey();
        long calls = usageMeter.getCurrentUsage(tenantId, monthKey, UsageMeter.RESOURCE_CALLS);
        long tokens = usageMeter.getCurrentUsage(tenantId, monthKey, UsageMeter.RESOURCE_TOKENS);
        return new QuotaSnapshot(quota, monthKey, calls, tokens);
    }

    private static long limitFor(TenantQuota quota, String resource) {
        return switch (resource) {
            case UsageMeter.RESOURCE_CALLS  -> quota.monthlyCallLimit();
            case UsageMeter.RESOURCE_TOKENS -> quota.monthlyTokenLimit();
            default -> throw new IllegalArgumentException("unknown resource: " + resource);
        };
    }

    private void refreshGauge(String tenantId, String resource, long usage, long limit) {
        String key = tenantId + ":" + resource;
        gaugeCache.computeIfAbsent(key, k -> {
            java.util.concurrent.atomic.AtomicReference<Double> holder =
                    new java.util.concurrent.atomic.AtomicReference<>(0.0);
            meterRegistry.gauge("tenant.quota.usage_ratio",
                    Tags.of("tenant", tenantId, "resource", resource),
                    holder,
                    h -> h.get());
            return holder;
        }).set(limit > 0 ? (double) usage / limit : 0.0);
    }

    /**
     * 当前快照 (GET /quota 返回值) — 只读视图.
     */
    public record QuotaSnapshot(
            TenantQuota quota,
            String monthKey,
            long currentCalls,
            long currentTokens
    ) {
        public double callUsageRatio() {
            return quota.monthlyCallLimit() > 0
                    ? (double) currentCalls / quota.monthlyCallLimit()
                    : 0.0;
        }

        public double tokenUsageRatio() {
            return quota.monthlyTokenLimit() > 0
                    ? (double) currentTokens / quota.monthlyTokenLimit()
                    : 0.0;
        }
    }
}