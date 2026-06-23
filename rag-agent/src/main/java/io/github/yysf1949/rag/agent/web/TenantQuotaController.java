package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.quota.TenantQuota;
import io.github.yysf1949.rag.agent.quota.TenantQuotaEnforcer;
import io.github.yysf1949.rag.agent.quota.TenantQuotaEnforcer.QuotaSnapshot;
import io.github.yysf1949.rag.agent.quota.TenantQuotaPort;
import io.github.yysf1949.rag.agent.quota.TenantTier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tenant 配额管理 API — Phase 40 T3 (R11: 商业化第一阶段).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/admin/tenant-quota/{tenantId}} — 查某租户的配额 + 当前用量快照</li>
 *   <li>{@code PUT /api/admin/tenant-quota/{tenantId}} — 覆盖配额 (admin 调整 tier/上限)</li>
 *   <li>{@code GET /api/admin/tenant-quota} — 列出全部租户配额</li>
 *   <li>{@code DELETE /api/admin/tenant-quota/{tenantId}/downgrade} — 清除降级态 (恢复 tier)</li>
 * </ul>
 *
 * <h2>鉴权</h2>
 * <p>{@code /api/admin/**} 路径在 {@code SecurityConfig} 中已要求 {@code ROLE_ADMIN}
 * (Phase 34 T34b 落地). 这里不再重复校验.</p>
 */
@RestController
@RequestMapping("/api/admin/tenant-quota")
@Tag(name = "Tenant Quota", description = "Phase 40 T3: 多租户用量配额管理 (R11).")
public class TenantQuotaController {

    private final TenantQuotaPort quotaPort;
    private final TenantQuotaEnforcer enforcer;

    public TenantQuotaController(TenantQuotaPort quotaPort, TenantQuotaEnforcer enforcer) {
        this.quotaPort = quotaPort;
        this.enforcer = enforcer;
    }

    /**
     * 查某租户的配额 + 当月用量快照.
     */
    @GetMapping("/{tenantId}")
    @Operation(summary = "查询租户配额 (T3)")
    public ResponseEntity<QuotaResponse> get(@PathVariable @NotBlank String tenantId) {
        QuotaSnapshot snap = enforcer.snapshot(tenantId);
        return ResponseEntity.ok(QuotaResponse.from(snap));
    }

    /**
     * 覆盖配额 — admin 调整 tier/上限. body 里不传 effectiveTo 表示长期有效.
     */
    @PutMapping("/{tenantId}")
    @Operation(summary = "覆盖租户配额 (T3)",
            description = "admin 调整 tier/上限. 如果新 tier != 当前 tier 且 quota 处于降级态, "
                    + "视作 admin 显式覆盖降级, 会同时清空 downgradedAt + originalTier.")
    public ResponseEntity<QuotaResponse> put(
            @PathVariable @NotBlank String tenantId,
            @Valid @RequestBody QuotaUpdateRequest req) {
        long now = System.currentTimeMillis();
        TenantQuota existing = quotaPort.findById(tenantId).orElse(null);
        TenantTier tier = req.tier() != null ? req.tier() : TenantTier.FREE;

        Long downgradedAt;
        TenantTier originalTier = null;
        if (existing != null && existing.downgradedAt() != null && tier != TenantTier.FREE) {
            // admin 显式把 tier 改回 PRO/ENTERPRISE → 视作强制覆盖降级态
            downgradedAt = null;
            originalTier = null;
        } else if (existing != null) {
            downgradedAt = existing.downgradedAt();
            originalTier = existing.originalTier();
        } else {
            downgradedAt = null;
            originalTier = null;
        }

        TenantQuota toSave = new TenantQuota(
                tenantId, tier,
                req.monthlyCallLimit() != null ? req.monthlyCallLimit() : tier.defaultMonthlyCallLimit(),
                req.monthlyTokenLimit() != null ? req.monthlyTokenLimit() : tier.defaultMonthlyTokenLimit(),
                now, req.effectiveTo(), downgradedAt, originalTier
        );
        quotaPort.save(toSave);
        return ResponseEntity.ok(QuotaResponse.from(enforcer.snapshot(tenantId)));
    }

    /**
     * 列全部租户配额 — 给 admin 仪表盘用.
     */
    @GetMapping
    @Operation(summary = "列出全部租户配额 (T3)")
    public ResponseEntity<List<QuotaResponse>> list() {
        List<QuotaResponse> out = quotaPort.findAll().stream()
                .map(q -> QuotaResponse.from(enforcer.snapshot(q.tenantId())))
                .toList();
        return ResponseEntity.ok(out);
    }

    /**
     * 清除降级态 — tier 保留, downgradedAt 置空. 下月 1 号 cron 也会自动调.
     */
    @PutMapping("/{tenantId}/clear-downgrade")
    @Operation(summary = "清除降级态 (T3)",
            description = "把 tier 保留, downgradedAt 置空. 恢复原始 tier.")
    public ResponseEntity<QuotaResponse> clearDowngrade(@PathVariable @NotBlank String tenantId) {
        quotaPort.clearDowngrade(tenantId);
        return ResponseEntity.ok(QuotaResponse.from(enforcer.snapshot(tenantId)));
    }

    /**
     * PUT body — tier + 月度上限.
     */
    public record QuotaUpdateRequest(
            TenantTier tier,
            @Min(0) Long monthlyCallLimit,
            @Min(0) Long monthlyTokenLimit,
            Long effectiveTo
    ) { }

    /**
     * GET 响应 — 配额 + 当前用量 + 占比.
     */
    public record QuotaResponse(
            @NotBlank String tenantId,
            @NotNull TenantTier tier,
            long monthlyCallLimit,
            long monthlyTokenLimit,
            long currentCalls,
            long currentTokens,
            String monthKey,
            double callUsageRatio,
            double tokenUsageRatio,
            Long downgradedAt,
            TenantTier originalTier
    ) {
        static QuotaResponse from(QuotaSnapshot snap) {
            TenantQuota q = snap.quota();
            return new QuotaResponse(
                    q.tenantId(),
                    q.tier(),
                    q.monthlyCallLimit(),
                    q.monthlyTokenLimit(),
                    snap.currentCalls(),
                    snap.currentTokens(),
                    snap.monthKey(),
                    snap.callUsageRatio(),
                    snap.tokenUsageRatio(),
                    q.downgradedAt(),
                    q.originalTier()
            );
        }
    }
}