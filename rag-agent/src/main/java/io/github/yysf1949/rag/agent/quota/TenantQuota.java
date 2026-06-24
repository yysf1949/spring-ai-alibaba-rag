package io.github.yysf1949.rag.agent.quota;

import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Tenant 配额实体 — Phase 40 T3 (R11: 商业化第一阶段).
 *
 * <h2>字段语义</h2>
 * <ul>
 *   <li>{@code tenantId} — 主键, 与 Feedback/Agent 调用等其他 tenant 维度硬对齐</li>
 *   <li>{@code tier} — 当前生效的层级 (FREE/PRO/ENTERPRISE); 超限后被强制改写为 FREE</li>
 *   <li>{@code monthlyCallLimit} — 当月允许的最大调用次数 (跨超限 → 拒绝 + 降级)</li>
 *   <li>{@code monthlyTokenLimit} — 当月允许的最大 token 数 (跨超限 → 拒绝 + 降级)</li>
 *   <li>{@code effectiveFrom} / {@code effectiveTo} — 配额生效窗口 (毫秒 epoch).
 *       {@code effectiveTo == null} 表示长期有效 (直到下次 PUT).</li>
 *   <li>{@code downgradedAt} — 上一次被 enforcer 强制降级的时间 (毫秒 epoch).
 *       非空表示当前处于"惩罚态", 直到下月 1 号或 admin 显式 PUT 才清除.</li>
 *   <li>{@code originalTier} — 降级前的原始 tier. 降级清除时 (admin 或下月 1 号) 恢复.
 *       未降级时为 null. 降级后必须等于 FREE 之前的 tier.</li>
 * </ul>
 *
 * <h2>不变量</h2>
 * <ul>
 *   <li>{@code monthlyCallLimit >= 0 && monthlyTokenLimit >= 0}</li>
 *   <li>若 {@code downgradedAt != null}, tier 必为 FREE (enforcer 强制写回)</li>
 *   <li>若 {@code downgradedAt != null}, originalTier 必非 null 且 != FREE</li>
 *   <li>{@code effectiveTo} 若非空, 必须 &gt; {@code effectiveFrom}</li>
 * </ul>
 */
public record TenantQuota(
        String tenantId,
        TenantTier tier,
        long monthlyCallLimit,
        long monthlyTokenLimit,
        long effectiveFrom,
        Long effectiveTo,
        Long downgradedAt,
        TenantTier originalTier
) {

    public TenantQuota {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required");
        }
        if (tier == null) {
            throw new IllegalArgumentException("tier required");
        }
        if (monthlyCallLimit < 0) {
            throw new IllegalArgumentException(
                    "monthlyCallLimit must be >= 0, got: " + monthlyCallLimit);
        }
        if (monthlyTokenLimit < 0) {
            throw new IllegalArgumentException(
                    "monthlyTokenLimit must be >= 0, got: " + monthlyTokenLimit);
        }
        if (effectiveFrom < 0) {
            throw new IllegalArgumentException(
                    "effectiveFrom must be >= 0, got: " + effectiveFrom);
        }
        if (effectiveTo != null) {
            if (effectiveTo <= effectiveFrom) {
                throw new IllegalArgumentException(
                        "effectiveTo must be > effectiveFrom, got from="
                                + effectiveFrom + " to=" + effectiveTo);
            }
        }
        if (downgradedAt != null && downgradedAt < 0) {
            throw new IllegalArgumentException(
                    "downgradedAt must be >= 0, got: " + downgradedAt);
        }
        // 不变量: 降级态 → tier 必须是 FREE
        if (downgradedAt != null && tier != TenantTier.FREE) {
            throw new IllegalArgumentException(
                    "downgradedAt set but tier is " + tier + " (must be FREE)");
        }
        // 不变量: 降级态 → originalTier 必非空且 != FREE
        if (downgradedAt != null && (originalTier == null || originalTier == TenantTier.FREE)) {
            throw new IllegalArgumentException(
                    "downgradedAt set but originalTier is " + originalTier
                            + " (must be a paid tier PRO/ENTERPRISE)");
        }
        // 不变量: 未降级态 → originalTier 必为空 (避免误用)
        if (downgradedAt == null && originalTier != null) {
            throw new IllegalArgumentException(
                    "downgradedAt is null but originalTier is " + originalTier
                            + " (must be null in normal state)");
        }
    }

    /**
     * 工厂: 全新租户按 tier 默认值建一份配额.
     *
     * @param tenantId 租户 ID
     * @param tier     初始层级
     * @param now      当前时间 (毫秒 epoch)
     */
    public static TenantQuota forNewTenant(String tenantId, TenantTier tier, long now) {
        return new TenantQuota(
                tenantId, tier,
                tier.defaultMonthlyCallLimit(),
                tier.defaultMonthlyTokenLimit(),
                now, null, null, null);
    }

    /**
     * 当前 UTC 月份 key — UsageMeter 按此分桶计数.
     * 格式: {@code YYYY-MM} (例如 2026-06).
     */
    public static String currentMonthKey() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }

    /**
     * 计算下个月 1 号 00:00 UTC 的 epoch 毫秒 — 给"下月重置"逻辑用.
     */
    public static long nextMonthStartEpochMs() {
        YearMonth next = YearMonth.now(ZoneOffset.UTC).plusMonths(1);
        return next.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /**
     * 返回"降级清除后的副本" — tier 恢复到 {@code originalTier}, downgradedAt 置空,
     * originalTier 自身也置空 (回到正常态). 用于下月 1 号定时任务或 admin 主动 reset.
     */
    public TenantQuota clearedDowngrade() {
        if (downgradedAt == null) {
            return this;  // 已经不在降级态, 不变
        }
        TenantTier restored = originalTier != null ? originalTier : TenantTier.FREE;
        return new TenantQuota(tenantId, restored,
                monthlyCallLimit, monthlyTokenLimit,
                effectiveFrom, effectiveTo, null, null);
    }

    /**
     * 标记降级 — tier 强制 FREE, downgradedAt = now, originalTier 记下当前 tier (只记一次).
     * 如果已经在降级态, 仅刷新 downgradedAt 时间, 不再覆盖 originalTier.
     */
    public TenantQuota markedDowngraded(long now) {
        if (downgradedAt != null) {
            // 已降级: 只刷新时间戳
            return new TenantQuota(tenantId, TenantTier.FREE,
                    monthlyCallLimit, monthlyTokenLimit,
                    effectiveFrom, effectiveTo, now, originalTier);
        }
        // 首次降级: 记下原始 tier
        return new TenantQuota(tenantId, TenantTier.FREE,
                monthlyCallLimit, monthlyTokenLimit,
                effectiveFrom, effectiveTo, now, tier);
    }
}